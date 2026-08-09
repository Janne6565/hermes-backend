package com.janne6565.hermes.services.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.repository.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The backfill spends real credit, runs detached from any request, and rewrites rows the user is
 * looking at. The properties worth pinning are the ones that bound it: rules before tokens, one run
 * at a time, a hard cap on calls, a clean stop when the sidecar dies, per-message commits, and
 * priorities left strictly alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryBackfillServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private CategoryMatcher categoryMatcher;
    @Mock private CategoryService categoryService;
    @Mock private SidecarClient sidecarClient;

    private CategoryBackfillService service;

    private CategoryEntity billing;
    private CategoryEntity alerts;

    /** Runs the callback inline, so the per-message transaction boundaries stay observable. */
    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    @BeforeEach
    void setUp() {
        billing = CategoryEntity.builder().name("Billing").color("#b8934a").build();
        alerts = CategoryEntity.builder().name("Alerts").color("#c98b74").build();
        service =
                new CategoryBackfillService(
                        messageRepository,
                        categoryMatcher,
                        categoryService,
                        sidecarClient,
                        inlineTransactions());

        when(categoryService.names()).thenReturn(List.of("Billing", "Alerts"));
        when(categoryService.findByName("Billing")).thenReturn(Optional.of(billing));
        when(categoryService.findByName("Alerts")).thenReturn(Optional.of(alerts));
        when(categoryService.findByName(null)).thenReturn(Optional.empty());
        when(categoryMatcher.matchStored(any())).thenReturn(Optional.empty());
    }

    private MessageEntity uncategorised(String sender) {
        MessageEntity message =
                MessageEntity.builder()
                        .provider(MailProviderType.GMAIL)
                        .externalId(sender)
                        .sender(sender)
                        .subject("Subject")
                        .receivedAt(Instant.now())
                        .priority(Priority.NOISE)
                        .classifiedBy(ClassifiedBy.RULE)
                        .categorySource(CategorySource.NONE)
                        .build();
        when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        return message;
    }

    private static SidecarClient.ClassificationResponse says(String category) {
        // The priority is deliberately the opposite of the stored one — the test is that it never
        // lands.
        return new SidecarClient.ClassificationResponse(
                Priority.HIGH, "reason", "summary", category, 0.9f, null);
    }

    /** Small local poll rather than a new test dependency for one wait condition. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("condition not met within 5s");
    }

    private void awaitIdle() {
        awaitUntil(() -> !service.status().running());
    }

    @Test
    void rulesRunBeforeAnyTokenIsSpent() {
        MessageEntity message = uncategorised("alerts@grafana.io");
        CategoryRuleEntity rule =
                CategoryRuleEntity.builder()
                        .category(alerts)
                        .type(RuleType.SENDER)
                        .pattern("*grafana*")
                        .createdAt(Instant.now())
                        .build();
        when(messageRepository.findUncategorised())
                .thenReturn(List.of(message))
                .thenReturn(List.of());
        when(categoryMatcher.matchStored("alerts@grafana.io")).thenReturn(Optional.of(rule));

        service.start(200);
        awaitIdle();

        assertThat(message.getCategorySource()).isEqualTo(CategorySource.RULE);
        verify(sidecarClient, never()).classify(any());
    }

    @Test
    void theLimitCapsClassifierCalls() {
        List<MessageEntity> many =
                IntStream.range(0, 10).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Billing")));

        service.start(3);
        awaitIdle();

        verify(sidecarClient, times(3)).classify(any());
        assertThat(service.status().processed()).isEqualTo(3);
    }

    @Test
    void aDeadSidecarStopsTheRunInsteadOfMarkingEverythingUnresolved() {
        List<MessageEntity> many =
                IntStream.range(0, 5).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any())).thenReturn(Optional.empty());

        service.start(200);
        awaitIdle();

        assertThat(service.status().lastOutcome()).isEqualTo("sidecar_unavailable");
        assertThat(many).allMatch(m -> m.getCategorySource() == CategorySource.NONE);
        // Three strikes, then out — not all five. Bailing on the first empty response was the
        // original behaviour and it turned one slow classification into a dead run.
        verify(sidecarClient, times(3)).classify(any());
    }

    @Test
    void anIsolatedFailureIsSkippedRatherThanEndingTheRun() {
        // The real failure mode was never "the sidecar is down" — it was one turn taking longer
        // than the client timeout. That message is lost to this run; the other four are not.
        List<MessageEntity> many =
                IntStream.range(0, 5).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any()))
                .thenReturn(Optional.of(says("Billing")))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(says("Billing")))
                .thenReturn(Optional.of(says("Alerts")))
                .thenReturn(Optional.of(says("Billing")));

        service.start(200);
        awaitIdle();

        assertThat(service.status().lastOutcome()).isNotEqualTo("sidecar_unavailable");
        verify(sidecarClient, times(5)).classify(any());
        assertThat(many.stream().filter(m -> m.getCategorySource() == CategorySource.LLM))
                .hasSize(4);
    }

    @Test
    void theFailureCounterResetsOnSuccess() {
        // Two failures, a success, then two more must not add up to a stop at three.
        List<MessageEntity> many =
                IntStream.range(0, 5).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(says("Billing")))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        service.start(200);
        awaitIdle();

        assertThat(service.status().lastOutcome()).isNotEqualTo("sidecar_unavailable");
        verify(sidecarClient, times(5)).classify(any());
    }

    @Test
    void aBackfillNeverRetriagesPriority() {
        MessageEntity message = uncategorised("someone@x.io");
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Billing")));

        service.start(200);
        awaitIdle();

        assertThat(message.getCategory()).isEqualTo(billing);
        assertThat(message.getPriority()).isEqualTo(Priority.NOISE);
        assertThat(message.getClassifiedBy()).isEqualTo(ClassifiedBy.RULE);
    }

    @Test
    void anUnusableAnswerIsLeftForTheNextRunRatherThanStamped() {
        MessageEntity message = uncategorised("someone@x.io");
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Nonsense")));
        when(categoryService.findByName("Nonsense")).thenReturn(Optional.empty());

        service.start(200);
        awaitIdle();

        // Still NONE, so findUncategorised() picks it up again next time.
        assertThat(message.getCategorySource()).isEqualTo(CategorySource.NONE);
    }

    @Test
    void aSecondPressWhileRunningIsANoOpRatherThanASecondBill() throws Exception {
        // The likeliest input this endpoint will ever get is an impatient second click.
        CountDownLatch release = new CountDownLatch(1);
        List<MessageEntity> many =
                IntStream.range(0, 3).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any()))
                .thenAnswer(
                        invocation -> {
                            release.await();
                            return Optional.of(says("Billing"));
                        });

        service.start(200);
        awaitUntil(() -> service.status().running());
        service.start(200);
        release.countDown();
        awaitIdle();

        // Three messages, one run — not six calls from two overlapping runs.
        verify(sidecarClient, times(3)).classify(any());
    }

    @Test
    void startingReturnsImmediatelyRatherThanWaitingOutTheRun() {
        // The whole point of the rewrite: the caller is not held open for the work.
        CountDownLatch release = new CountDownLatch(1);
        MessageEntity message = uncategorised("a@x.io");
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(sidecarClient.classify(any()))
                .thenAnswer(
                        invocation -> {
                            release.await();
                            return Optional.of(says("Billing"));
                        });

        long before = System.nanoTime();
        service.start(200);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1_000);
        release.countDown();
        awaitIdle();
    }

    @Test
    void nothingToDoIsAnEmptyRunNotAnError() {
        when(messageRepository.findUncategorised()).thenReturn(List.of());

        service.start(200);
        awaitIdle();

        assertThat(service.status().lastOutcome()).isEqualTo("done");
        verify(sidecarClient, never()).classify(any());
    }
}
