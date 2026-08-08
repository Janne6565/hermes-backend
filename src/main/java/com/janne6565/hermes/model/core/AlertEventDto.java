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
        boolean notified) {

    public static AlertEventDto from(AlertEventEntity entity) {
        return new AlertEventDto(
                entity.getId(),
                entity.getSource(),
                entity.getSeverity(),
                entity.getTitle(),
                entity.getAppName(),
                entity.getReceivedAt(),
                entity.getResolvedAt(),
                entity.isNotified());
    }
}
