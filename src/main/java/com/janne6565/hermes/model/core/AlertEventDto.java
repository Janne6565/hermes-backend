package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.AlertEventEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An alert received over the webhook intake")
public record AlertEventDto(
        UUID id,
        AlertSource source,
        AlertSeverity severity,
        String title,
        String app,
        Instant receivedAt,
        Instant resolvedAt,
        boolean notified,
        @Schema(description = "When the operator acknowledged it — not the same as resolved")
                Instant acknowledgedAt,
        @Schema(description = "Pushes for this alert are suppressed until this instant")
                Instant snoozedUntil,
        @Schema(description = "Link back to the tool that owns this alert, when one is configured")
                String sourceUrl) {

    public static AlertEventDto from(AlertEventEntity entity) {
        return from(entity, null);
    }

    public static AlertEventDto from(AlertEventEntity entity, String sourceUrl) {
        return new AlertEventDto(
                entity.getId(),
                entity.getSource(),
                entity.getSeverity(),
                entity.getTitle(),
                entity.getAppName(),
                entity.getReceivedAt(),
                entity.getResolvedAt(),
                entity.isNotified(),
                entity.getAcknowledgedAt(),
                entity.getSnoozedUntil(),
                sourceUrl);
    }
}
