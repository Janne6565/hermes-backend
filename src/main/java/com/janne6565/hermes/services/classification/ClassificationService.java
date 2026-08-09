package com.janne6565.hermes.services.classification;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.categories.CategoryMatcher;
import com.janne6565.hermes.services.categories.CategoryService;
import com.janne6565.hermes.services.notification.NotificationService;
import com.janne6565.hermes.services.rules.RuleEngine;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one message through the pipeline: hard rules, then the classifier, then the fallback.
 *
 * <p>The fallback tier is what keeps a dead sidecar from being a silent failure. An unclassifiable
 * message is stored as {@code normal} and flagged {@code fallback}, which does two things: it never
 * pushes (so a broken classifier cannot spam the phone), and it shows up as a warning line in the
 * digest and a re-classification candidate for the nightly job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassificationService {

    private final RuleEngine ruleEngine;
    private final SidecarClient sidecarClient;
    private final MessageRepository messageRepository;
    private final NotificationService notificationService;
    private final CategoryMatcher categoryMatcher;
    private final CategoryService categoryService;
    private final HermesProperties properties;

    /**
     * Cheap "have we already stored this?" check, so the sync loop can skip a known message without
     * paying for a provider fetch and a classifier call.
     */
    @Transactional(readOnly = true)
    public boolean alreadySeen(MailProviderType provider, String externalId) {
        return messageRepository.existsByProviderAndExternalId(provider, externalId);
    }

    /**
     * Classifies and persists a freshly fetched message, pushing if it earns an interrupt.
     *
     * @return the stored row, or empty if we had already seen this Gmail id.
     */
    @Transactional
    public Optional<MessageEntity> ingest(FetchedMessage fetched) {
        if (messageRepository.existsByProviderAndExternalId(
                fetched.provider(), fetched.externalId())) {
            return Optional.empty();
        }

        Verdict verdict = classify(fetched);
        MessageEntity message =
                MessageEntity.builder()
                        .provider(fetched.provider())
                        .externalId(fetched.externalId())
                        .sender(fetched.sender())
                        .subject(fetched.subject())
                        .snippet(fetched.snippet())
                        .receivedAt(fetched.receivedAt())
                        .priority(verdict.priority())
                        .reason(verdict.reason())
                        .summary(verdict.summary())
                        .classifiedBy(verdict.classifiedBy())
                        .build();

        applyCategory(message, verdict.category());
        messageRepository.save(message);

        if (verdict.priority() == Priority.HIGH) {
            notificationService.pushHighPriorityMail(message);
        }
        return Optional.of(message);
    }

    /**
     * Nightly repair pass: re-runs the classifier over everything the sidecar missed.
     *
     * <p>These deliberately do <em>not</em> push even when they come back {@code high} — the moment
     * to interrupt has passed, and a 3am burst of notifications for yesterday's mail is exactly the
     * behaviour this service exists to eliminate. They surface in the next digest instead.
     */
    @Transactional
    public int reclassifyFallbacks() {
        if (!sidecarClient.isHealthy()) {
            log.info("Sidecar still unhealthy; skipping fallback re-classification");
            return 0;
        }

        List<MessageEntity> pending =
                messageRepository.findByClassifiedByOrderByReceivedAtAsc(ClassifiedBy.FALLBACK);
        int repaired = 0;

        for (MessageEntity message : pending) {
            Optional<SidecarClient.ClassificationResponse> response =
                    sidecarClient.classify(
                            new SidecarClient.ClassificationRequest(
                                    message.getSender(),
                                    message.getSubject(),
                                    message.getSnippet(),
                                    categoryService.names()));
            if (response.isEmpty()) {
                log.warn("Sidecar went unhealthy mid-retry; stopping after {} messages", repaired);
                break;
            }
            SidecarClient.ClassificationResponse verdict = response.get();
            message.setPriority(verdict.priority());
            message.setReason(verdict.reason());
            message.setSummary(verdict.summary());
            message.setClassifiedBy(ClassifiedBy.LLM);
            // Only fill a category the pipeline never resolved. A rule hit or the user's own
            // correction outranks the retry, which is running hours after they made the call.
            if (message.getCategorySource() == null
                    || message.getCategorySource() == CategorySource.NONE) {
                applyCategory(message, fromModel(verdict));
            }
            repaired++;
        }

        if (repaired > 0) {
            log.info("Re-classified {} fallback messages", repaired);
        }
        return repaired;
    }

    /**
     * Resolves both axes for a freshly fetched message.
     *
     * <p>The two axes are resolved independently, and that costs something. A priority rule hit
     * used to end the pipeline with no LLM turn at all; now, if no category rule also matched, the
     * classifier is still asked — for the category alone, with its priority verdict discarded,
     * because a hard rule outranks the model on that axis.
     *
     * <p>The alternative was leaving those messages permanently uncategorised, which is what the
     * first cut did: about a third of this mailbox is settled by hard rules, so a third of the
     * categories screen was a single grey bar. A feature that only works for the mail the model
     * happens to see is not worth the column. Switchable via {@code
     * hermes.categories.classify-rule-hits} when credit matters more than coverage.
     */
    private Verdict classify(FetchedMessage fetched) {
        Optional<CategoryRuleEntity> categoryRule = categoryMatcher.match(fetched);
        CategoryVerdict byRule =
                categoryRule
                        .map(rule -> new CategoryVerdict(rule.getCategory(), CategorySource.RULE))
                        .orElse(null);

        Optional<RuleEngine.Match> ruleMatch = ruleEngine.evaluate(fetched);
        if (ruleMatch.isPresent()) {
            RuleEngine.Match match = ruleMatch.get();
            return new Verdict(
                    match.priority(),
                    match.reason(),
                    truncateSubject(fetched),
                    ClassifiedBy.RULE,
                    byRule != null ? byRule : categoryOnly(fetched));
        }

        Optional<SidecarClient.ClassificationResponse> llm =
                sidecarClient.classify(
                        new SidecarClient.ClassificationRequest(
                                fetched.sender(),
                                fetched.subject(),
                                fetched.snippet(),
                                categoryService.names()));

        return llm.map(
                        response ->
                                new Verdict(
                                        response.priority(),
                                        response.reason(),
                                        response.summary(),
                                        ClassifiedBy.LLM,
                                        // A rule beats the model on the category too: the user's
                                        // own statement about a sender is not a hypothesis.
                                        byRule != null ? byRule : fromModel(response)))
                .orElseGet(
                        () ->
                                new Verdict(
                                        Priority.NORMAL,
                                        "classifier unavailable",
                                        truncateSubject(fetched),
                                        ClassifiedBy.FALLBACK,
                                        byRule != null ? byRule : unresolvedCategory()));
    }

    /**
     * Asks the classifier for a category when a priority rule already answered the other question.
     *
     * <p>The response's priority is read and thrown away on purpose: the rule won that axis before
     * this call was made, and letting the model's opinion in through the back door is exactly the
     * leak the two-axis separation exists to prevent.
     */
    private CategoryVerdict categoryOnly(FetchedMessage fetched) {
        if (!properties.getCategories().isClassifyRuleHits()) {
            return unresolvedCategory();
        }
        return sidecarClient
                .classify(
                        new SidecarClient.ClassificationRequest(
                                fetched.sender(),
                                fetched.subject(),
                                fetched.snippet(),
                                categoryService.names()))
                .map(this::fromModel)
                // A sidecar outage must not turn a perfectly good rule verdict into a failure. The
                // message keeps its priority and lands in the fallback bucket, where the backfill
                // will find it later.
                .orElseGet(this::unresolvedCategory);
    }

    /** Maps the classifier's free-text category name onto a row, or gives up cleanly. */
    private CategoryVerdict fromModel(SidecarClient.ClassificationResponse response) {
        Optional<CategoryEntity> named = categoryService.findByName(response.category());
        if (named.isEmpty()) {
            return unresolvedCategory();
        }
        return new CategoryVerdict(
                named.get(),
                CategorySource.LLM,
                response.categoryConfidence(),
                categoryService.findByName(response.categoryAlternative()).orElse(null));
    }

    private CategoryVerdict unresolvedCategory() {
        return new CategoryVerdict(categoryService.fallback().orElse(null), CategorySource.NONE);
    }

    private static void applyCategory(MessageEntity message, CategoryVerdict category) {
        message.setCategory(category.category());
        message.setCategorySource(category.source());
        message.setCategoryConfidence(category.confidence());
        message.setCategoryAlternative(category.alternative());
    }

    /** Rule and fallback paths have no LLM summary; the subject is the honest stand-in. */
    private static String truncateSubject(FetchedMessage fetched) {
        String subject = fetched.subject();
        return subject.length() <= 120 ? subject : subject.substring(0, 117) + "...";
    }

    private record Verdict(
            Priority priority,
            String reason,
            String summary,
            ClassifiedBy classifiedBy,
            CategoryVerdict category) {}

    /**
     * @param confidence null for anything a rule settled — a rule is not a guess with a number.
     */
    private record CategoryVerdict(
            CategoryEntity category,
            CategorySource source,
            Float confidence,
            CategoryEntity alternative) {

        CategoryVerdict(CategoryEntity category, CategorySource source) {
            this(category, source, null, null);
        }
    }
}
