package com.janne6565.hermes.services.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.repository.CategoryRuleRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Category matching has a different resolution order from the priority engine — most specific
 * wins, newest first within a tier — and getting that order wrong is invisible until a seeded
 * pattern quietly overrules something the user just corrected.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMatcherTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private CategoryRuleRepository categoryRuleRepository;

    @InjectMocks private CategoryMatcher matcher;

    private static CategoryEntity category(String name) {
        return CategoryEntity.builder().name(name).color("#6b8fa8").build();
    }

    private static CategoryRuleEntity rule(
            CategoryEntity category, RuleType type, String pattern, Instant createdAt) {
        return CategoryRuleEntity.builder()
                .category(category)
                .type(type)
                .pattern(pattern)
                .createdAt(createdAt)
                .build();
    }

    private static FetchedMessage message(String sender, Map<String, String> headers) {
        return new FetchedMessage(
                MailProviderType.GMAIL,
                "id-1",
                sender,
                "Subject",
                "snippet",
                Instant.now(),
                headers);
    }

    @Test
    void aSenderRuleBeatsADomainRuleForTheSameMessage() {
        CategoryEntity billing = category("Billing");
        CategoryEntity infrastructure = category("Infrastructure");
        when(categoryRuleRepository.findAll())
                .thenReturn(
                        List.of(
                                rule(infrastructure, RuleType.DOMAIN, "hetzner.com", OLD),
                                rule(billing, RuleType.SENDER, "billing@hetzner.com", OLD)));

        Optional<CategoryRuleEntity> hit =
                matcher.match(message("billing@hetzner.com", Map.of()));

        assertThat(hit).isPresent();
        assertThat(hit.get().getCategory().getName()).isEqualTo("Billing");
    }

    @Test
    void aDomainRuleBeatsAHeaderRule() {
        CategoryEntity newsletters = category("Newsletters");
        CategoryEntity people = category("People");
        when(categoryRuleRepository.findAll())
                .thenReturn(
                        List.of(
                                rule(newsletters, RuleType.HEADER, "List-Unsubscribe", OLD),
                                rule(people, RuleType.DOMAIN, "uni-potsdam.de", OLD)));

        Optional<CategoryRuleEntity> hit =
                matcher.match(
                        message(
                                "Anna <weber@uni-potsdam.de>",
                                Map.of("list-unsubscribe", "<mailto:x@y.z>")));

        assertThat(hit).isPresent();
        assertThat(hit.get().getCategory().getName()).isEqualTo("People");
    }

    @Test
    void aFreshCorrectionBeatsASeededPatternOfTheSameKind() {
        // The user just said "this sender is Billing". A seeded '*hetzner*' rule from the migration
        // must not keep dragging it back to Infrastructure on the next poll.
        CategoryEntity billing = category("Billing");
        CategoryEntity infrastructure = category("Infrastructure");
        when(categoryRuleRepository.findAll())
                .thenReturn(
                        List.of(
                                rule(infrastructure, RuleType.SENDER, "*hetzner*", OLD),
                                rule(
                                        billing,
                                        RuleType.SENDER,
                                        "billing@hetzner.com",
                                        OLD.plus(30, ChronoUnit.DAYS))));

        Optional<CategoryRuleEntity> hit =
                matcher.match(message("billing@hetzner.com", Map.of()));

        assertThat(hit).isPresent();
        assertThat(hit.get().getCategory().getName()).isEqualTo("Billing");
    }

    @Test
    void countsAHitOnTheRuleThatMatched() {
        CategoryEntity alerts = category("Alerts");
        CategoryRuleEntity grafana = rule(alerts, RuleType.SENDER, "*grafana*", OLD);
        when(categoryRuleRepository.findAll()).thenReturn(List.of(grafana));

        matcher.match(message("alerts@grafana.example.com", Map.of()));

        assertThat(grafana.getHits()).isEqualTo(1);
    }

    @Test
    void noMatchLeavesTheDecisionToTheClassifier() {
        when(categoryRuleRepository.findAll()).thenReturn(List.of());

        assertThat(matcher.match(message("someone@example.com", Map.of()))).isEmpty();
    }
}
