package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.RuleEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A hard rule and how often it has fired")
public record RuleDto(
        UUID id,
        RuleType type,
        String pattern,
        Priority priority,
        boolean enabled,
        RuleSource source,
        long hits,
        Instant createdAt) {

    public static RuleDto from(RuleEntity entity) {
        return new RuleDto(
                entity.getId(),
                entity.getType(),
                entity.getPattern(),
                entity.getPriority(),
                entity.isEnabled(),
                entity.getSource(),
                entity.getHits(),
                entity.getCreatedAt());
    }
}
