package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Outcome of a manual test push — what the settings screen shows next to the button. */
@Schema(description = "Result of a test push to the ntfy topic")
public record TestPushResultDto(
        boolean delivered,
        String topic,
        @Schema(description = "Round-trip to ntfy, in milliseconds") long durationMs,
        Instant sentAt) {}
