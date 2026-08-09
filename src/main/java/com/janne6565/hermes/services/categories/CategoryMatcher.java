package com.janne6565.hermes.services.categories;

import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.repository.CategoryRuleRepository;
import com.janne6565.hermes.services.rules.RuleEngine;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage one of categorisation: deterministic rules, evaluated before the classifier is consulted.
 *
 * <p>There are no tiers here, and that is the difference from {@link RuleEngine}. Priority tiers
 * exist because getting the priority wrong has an asymmetric cost — a silenced deadline is much
 * worse than a stray interrupt — so an allowlist has to be able to beat a blocklist. Categories are
 * symmetric: being wrong about the topic costs the same in either direction. So the rule that makes
 * the most specific claim about the message wins: address, then domain, then header.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryMatcher {

    /** Most specific first. A rule about one address outranks one about the whole domain. */
    private static final List<RuleType> SPECIFICITY =
            List.of(RuleType.SENDER, RuleType.DOMAIN, RuleType.HEADER);

    private final CategoryRuleRepository categoryRuleRepository;

    /**
     * @return the matched rule, or empty when no rule speaks to this message and the classifier
     *     should be asked instead.
     */
    @Transactional
    public Optional<CategoryRuleEntity> match(FetchedMessage message) {
        List<CategoryRuleEntity> rules = categoryRuleRepository.findAll();

        for (RuleType type : SPECIFICITY) {
            Optional<CategoryRuleEntity> hit =
                    rules.stream()
                            .filter(rule -> rule.getType() == type)
                            // Newest wins within a tier: a correction the user made today should
                            // beat a seeded pattern from the migration.
                            .sorted(
                                    Comparator.comparing(CategoryRuleEntity::getCreatedAt)
                                            .reversed())
                            .filter(rule -> matches(rule, message))
                            .findFirst();
            if (hit.isPresent()) {
                CategoryRuleEntity rule = hit.get();
                rule.setHits(rule.getHits() + 1);
                log.debug(
                        "Category rule {} ({} {}) matched message {}",
                        rule.getId(),
                        rule.getType().wire(),
                        rule.getPattern(),
                        message.externalId());
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(CategoryRuleEntity rule, FetchedMessage message) {
        return switch (rule.getType()) {
            case SENDER -> RuleEngine.globMatches(
                    rule.getPattern(), RuleEngine.emailAddress(message.sender()));
            case DOMAIN -> RuleEngine.globMatches(
                    rule.getPattern(), RuleEngine.domainOf(message.sender()));
            case HEADER -> headerMatches(rule.getPattern(), message);
        };
    }

    /** Same two forms as a priority header rule: a bare name, or {@code Name: value}. */
    private static boolean headerMatches(String pattern, FetchedMessage message) {
        int separator = pattern.indexOf(':');
        if (separator < 0) {
            return message.headers().containsKey(pattern.trim().toLowerCase(Locale.ROOT));
        }
        String name = pattern.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String expected = pattern.substring(separator + 1).trim();
        String actual = message.headers().get(name);
        return actual != null && RuleEngine.globMatches(expected, actual);
    }
}
