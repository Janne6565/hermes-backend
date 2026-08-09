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
import com.janne6565.hermes.model.core.CategoryBackfillDto;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.repository.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The backfill spends real credit and rewrites rows the user has already seen, so the properties
 * that matter are the ones that bound it: rules before tokens, a hard cap on calls, a clean stop
 * when the sidecar dies, and priorities left strictly alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryBackfillServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private CategoryMatcher categoryMatcher;
    @Mock private CategoryService categoryService;
    @Mock private SidecarClient sidecarClient;

    @InjectMocks private CategoryBackfillService service;

    private CategoryEntity billing;
    private CategoryEntity alerts;

    @BeforeEach
    void setUp() {
        billing = CategoryEntity.builder().name("Billing").color("#b8934a").build();
        alerts = CategoryEntity.builder().name("Alerts").color("#c98b74").build();
        when(categoryService.names()).thenReturn(List.of("Billing", "Alerts"));
        when(categoryService.findByName("Billing")).thenReturn(Optional.of(billing));
        when(categoryService.findByName("Alerts")).thenReturn(Optional.of(alerts));
        when(categoryMatcher.matchStored(any())).thenReturn(Optional.empty());
    }

    private static MessageEntity uncategorised(String sender) {
        return MessageEntity.builder()
                .provider(MailProviderType.GMAIL)
                .externalId(sender)
                .sender(sender)
                .subject("Subject")
                .receivedAt(Instant.now())
                .priority(Priority.NOISE)
                .classifiedBy(ClassifiedBy.RULE)
                .categorySource(CategorySource.NONE)
                .build();
    }

    private static SidecarClient.ClassificationResponse says(String category) {
        // The priority here is deliberately the opposite of the stored one — the test is that it
        // never lands.
        return new SidecarClient.ClassificationResponse(
                Priority.HIGH, "reason", "summary", category, 0.9f, null);
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
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(categoryMatcher.matchStored("alerts@grafana.io")).thenReturn(Optional.of(rule));

        CategoryBackfillDto result = service.run(200);

        assertThat(result.categorisedByRule()).isEqualTo(1);
        assertThat(result.categorisedByModel()).isZero();
        assertThat(message.getCategorySource()).isEqualTo(CategorySource.RULE);
        verify(sidecarClient, never()).classify(any());
    }

    @Test
    void theLimitCapsClassifierCallsAndReportsWhatIsLeft() {
        List<MessageEntity> many =
                IntStream.range(0, 10).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Billing")));

        CategoryBackfillDto result = service.run(3);

        assertThat(result.categorisedByModel()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(7);
        assertThat(result.stoppedBecause()).isEqualTo("limit");
        verify(sidecarClient, times(3)).classify(any());
    }

    @Test
    void aDeadSidecarStopsTheRunInsteadOfMarkingEverythingUnresolved() {
        List<MessageEntity> many =
                IntStream.range(0, 5).mapToObj(i -> uncategorised("a" + i + "@x.io")).toList();
        when(messageRepository.findUncategorised()).thenReturn(many);
        when(sidecarClient.classify(any())).thenReturn(Optional.empty());

        CategoryBackfillDto result = service.run(200);

        assertThat(result.stoppedBecause()).isEqualTo("sidecar_unavailable");
        assertThat(result.categorisedByModel()).isZero();
        assertThat(many).allMatch(m -> m.getCategorySource() == CategorySource.NONE);
        // One attempt, then out — not five.
        verify(sidecarClient, times(1)).classify(any());
    }

    @Test
    void aBackfillNeverRetriagesPriority() {
        MessageEntity message = uncategorised("someone@x.io");
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Billing")));

        service.run(200);

        assertThat(message.getCategory()).isEqualTo(billing);
        assertThat(message.getPriority()).isEqualTo(Priority.NOISE);
        assertThat(message.getClassifiedBy()).isEqualTo(ClassifiedBy.RULE);
    }

    @Test
    void anUnusableAnswerIsLeftForTheNextRunRatherThanStamped() {
        MessageEntity message = uncategorised("someone@x.io");
        when(messageRepository.findUncategorised()).thenReturn(List.of(message));
        when(sidecarClient.classify(any())).thenReturn(Optional.of(says("Nonsense")));

        CategoryBackfillDto result = service.run(200);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.categorisedByModel()).isZero();
        // Still NONE, so findUncategorised() picks it up again next time.
        assertThat(message.getCategorySource()).isEqualTo(CategorySource.NONE);
    }

    @Test
    void nothingToDoIsAnEmptyRunNotAnError() {
        when(messageRepository.findUncategorised()).thenReturn(List.of());

        CategoryBackfillDto result = service.run(200);

        assertThat(result.candidates()).isZero();
        assertThat(result.stoppedBecause()).isNull();
        verify(sidecarClient, never()).classify(any());
    }
}
