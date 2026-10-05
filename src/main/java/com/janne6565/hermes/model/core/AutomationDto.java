package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.AutomationEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A natural-language trigger and the actions it runs")
public record AutomationDto(
        UUID id,
        String name,
        String trigger,
        AutomationAlert alert,
        String webhookUrl,
        boolean enabled,
        @Schema(description = "How often it has fired, test runs excluded") long fireCount,
        Instant lastFiredAt,
        Instant createdAt) {

    public static AutomationDto from(AutomationEntity entity) {
        return new AutomationDto(
                entity.getId(),
                entity.getName(),
                entity.getTrigger(),
                entity.getAlert(),
                entity.getWebhookUrl(),
                entity.isEnabled(),
                entity.getFireCount(),
                entity.getLastFiredAt(),
                entity.getCreatedAt());
    }
}
