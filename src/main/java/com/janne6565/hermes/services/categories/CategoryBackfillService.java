package com.janne6565.hermes.services.categories;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.BackfillStatusDto;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.repository.MessageRepository;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Categorises mail that predates the feature.
 *
 * <p>The first cut of this ran inline, inside one {@code @Transactional} request handler. That was
 * wrong in three ways at once and all three showed up on the first real run: the HTTP call blocked
 * for as long as the work took, a Postgres transaction sat {@code idle in transaction} for minutes
 * while the thread waited on the classifier, and because everything committed at the end, five
 * minutes of successful classifications were still invisible — and would have been lost entirely to
 * a pod restart.
 *
 * <p>So the work is detached from the request and the transaction is scoped to the smallest thing
 * worth keeping: one message. Each classification commits on its own, which makes the run
 * observable while it happens — the categories screen already polls, so the fallback count simply
 * falls — and makes an interruption cost one message rather than all of them.
 *
 * <p>Two passes, cheap first. Category rules cost nothing, so they run over the whole set in one
 * short transaction before a single token is spent; only what they miss reaches the classifier.
 */
@Service
@Slf4j
public class CategoryBackfillService {

    /**
     * How many failures in a row mean "the classifier is down" rather than "that one was slow".
     * Three, because the run is resumable — giving up early costs a button press, and grinding on
     * through a genuine outage costs a timeout per remaining message.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final MessageRepository messageRepository;
    private final CategoryMatcher categoryMatcher;
    private final CategoryService categoryService;
    private final SidecarClient sidecarClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * One run at a time. Two concurrent runs would classify the same messages twice and bill for
     * the privilege, and the second press of a button that looks unresponsive is the likeliest
     * input this endpoint will ever get.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger target = new AtomicInteger();
    private volatile String lastOutcome;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    runnable -> Thread.ofPlatform().name("category-backfill").unstarted(runnable));

    public CategoryBackfillService(
            MessageRepository messageRepository,
            CategoryMatcher categoryMatcher,
            CategoryService categoryService,
            SidecarClient sidecarClient,
            TransactionTemplate transactionTemplate) {
        this.messageRepository = messageRepository;
        this.categoryMatcher = categoryMatcher;
        this.categoryService = categoryService;
        this.sidecarClient = sidecarClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Starts a run and returns immediately.
     *
     * @param limit cap on classifier calls, so a large mailbox is several deliberate runs rather
     *     than one surprise bill.
     * @return the state the caller should render — never a result, because there isn't one yet.
     */
    public BackfillStatusDto start(int limit) {
        if (!running.compareAndSet(false, true)) {
            return status();
        }
        processed.set(0);
        target.set(0);
        lastOutcome = null;

        try {
            executor.submit(
                    () -> {
                        try {
                            runPasses(limit);
                        } catch (RuntimeException exception) {
                            lastOutcome = "failed";
                            log.error("Category backfill failed", exception);
                        } finally {
                            running.set(false);
                        }
                    });
        } catch (RuntimeException exception) {
            // Submission itself failed (shutting down); do not leave the flag stuck on.
            running.set(false);
            throw exception;
        }
        return status();
    }

    /** What the screen polls while a run is in flight. */
    public BackfillStatusDto status() {
        return new BackfillStatusDto(
                running.get(),
                processed.get(),
                target.get(),
                (int) messageRepository.countUncategorised(),
                lastOutcome);
    }

    private void runPasses(int limit) {
        int byRule = applyRules();

        List<Candidate> candidates =
                transactionTemplate.execute(
                        transaction ->
                                messageRepository.findUncategorised().stream()
                                        .map(
                                                message ->
                                                        new Candidate(
                                                                message.getId(),
                                                                message.getSender(),
                                                                message.getSubject(),
                                                                message.getSnippet()))
                                        .limit(limit)
                                        .toList());
        if (candidates == null) {
            candidates = List.of();
        }
        target.set(candidates.size());

        List<String> vocabulary = categoryService.names();
        int byModel = 0;
        int skipped = 0;
        int consecutiveFailures = 0;

        for (Candidate candidate : candidates) {
            // Deliberately outside any transaction: this is the slow part, and holding a database
            // connection across it is what made the first version pathological.
            Optional<SidecarClient.ClassificationResponse> response =
                    sidecarClient.classify(
                            new SidecarClient.ClassificationRequest(
                                    candidate.sender(),
                                    candidate.subject(),
                                    candidate.snippet(),
                                    vocabulary));

            if (response.isEmpty()) {
                // One failure is not an outage. The first version bailed on any empty response,
                // which was right when the only failure mode was "the sidecar is down" — but the
                // real one turned out to be an occasional slow turn, and a whole run dying after
                // two messages because one was slow is worse than skipping that message.
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    lastOutcome = "sidecar_unavailable";
                    log.warn(
                            "Backfill stopped after {} consecutive classifier failures ({} done)",
                            consecutiveFailures,
                            byModel);
                    break;
                }
                log.debug("Classifier failed for one message; continuing");
                continue;
            }
            consecutiveFailures = 0;
            if (persist(candidate.id(), response.get())) {
                byModel++;
            } else {
                skipped++;
            }
            processed.incrementAndGet();
        }

        if (lastOutcome == null) {
            lastOutcome = messageRepository.countUncategorised() > 0 ? "more_remaining" : "done";
        }
        log.info(
                "Category backfill finished: {} by rule, {} by model, {} skipped ({})",
                byRule,
                byModel,
                skipped,
                lastOutcome);
    }

    /**
     * Free and fast, so it runs over everything in one short transaction before any token spend.
     */
    private int applyRules() {
        Integer applied =
                transactionTemplate.execute(
                        transaction -> {
                            int count = 0;
                            for (MessageEntity message : messageRepository.findUncategorised()) {
                                Optional<CategoryRuleEntity> hit =
                                        categoryMatcher.matchStored(message.getSender());
                                if (hit.isPresent()) {
                                    message.setCategory(hit.get().getCategory());
                                    message.setCategorySource(CategorySource.RULE);
                                    message.setCategoryConfidence(null);
                                    count++;
                                }
                            }
                            return count;
                        });
        return applied == null ? 0 : applied;
    }

    /**
     * @return true when the classifier's answer was usable. An unusable one leaves the row alone so
     *     the next run retries it, rather than stamping a source that would hide it for good.
     */
    private boolean persist(UUID messageId, SidecarClient.ClassificationResponse response) {
        Boolean stored =
                transactionTemplate.execute(
                        transaction -> {
                            Optional<CategoryEntity> named =
                                    categoryService.findByName(response.category());
                            Optional<MessageEntity> found = messageRepository.findById(messageId);
                            if (named.isEmpty() || found.isEmpty()) {
                                return false;
                            }
                            MessageEntity message = found.get();
                            // The priority in this response is ignored. These messages were already
                            // triaged, in some cases by a hard rule, and re-deciding that here
                            // would silently re-triage the mailbox under a backfill's name.
                            message.setCategory(named.get());
                            message.setCategorySource(CategorySource.LLM);
                            message.setCategoryConfidence(response.categoryConfidence());
                            message.setCategoryAlternative(
                                    categoryService
                                            .findByName(response.categoryAlternative())
                                            .orElse(null));
                            return true;
                        });
        return Boolean.TRUE.equals(stored);
    }

    @PreDestroy
    void shutDown() {
        // The run is resumable by construction, so a shutdown mid-pass costs at most the message
        // currently in flight — no reason to make the pod wait it out.
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A detached snapshot, so the slow classifier call needs no open session. */
    private record Candidate(UUID id, String sender, String subject, String snippet) {}
}
