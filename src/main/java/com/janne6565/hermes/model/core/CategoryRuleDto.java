package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.CategoryRuleEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * One "mail matching this pattern is about X" rule.
 *
 * <p>Carries no priority, and that absence is the feature — see {@link
 * com.janne6565.hermes.entity.CategoryRuleEntity}.
 */
@Schema(description = "A pattern that files mail into a category")
public record CategoryRuleDto(
        UUID id,
        RuleType type,
        String pattern,
        RuleSource source,
        @Schema(description = "How often it has matched") long hits) {

    public static CategoryRuleDto from(CategoryRuleEntity entity) {
        return new CategoryRuleDto(
                entity.getId(),
                entity.getType(),
                entity.getPattern(),
                entity.getSource(),
                entity.getHits());
    }
}
