package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * An ad-hoc digest over a span of days, built on request and never delivered.
 *
 * <p>Deliberately a separate record from {@link DigestDto} rather than the same one with a nullable
 * second date. A daily digest is a record of something that was <em>sent</em> — it carries {@code
 * sentAt}, it is stored under a unique date, and the app reads it back as the thing that reached
 * the phone. This is a report someone asked for just now: it has no delivery, no history and no row
 * in the database, and giving it the same shape would invite exactly that confusion.
 *
 * <p>The nested {@link DigestDto.Counts} and {@link DigestDto.NoiseSummary} are shared on purpose —
 * the arithmetic of a digest does not change with its span, and the app renders both through the
 * same component.
 */
@Schema(description = "A digest over a chosen span of days, built on demand and never delivered")
public record DigestRangeDto(
        @Schema(description = "First day of the span, inclusive") LocalDate from,
        @Schema(description = "Last day of the span, inclusive") LocalDate to,
        @Schema(description = "How many days the span covers, both ends included") int days,
        DigestDto.Counts counts,
        @Schema(
                        description =
                                "A few sentences of German prose describing the span. Null when the"
                                        + " narrator was unavailable — the lists below are always the"
                                        + " source of truth.")
                String narrative,
        List<MessageDto> high,
        List<MessageDto> normal,
        DigestDto.NoiseSummary noise,
        List<AlertEventDto> alerts,
        @Schema(description = "Messages in the span still awaiting classification")
                int unclassified,
        @Schema(description = "True when the classifier was unavailable for part of the span")
                boolean degraded,
        @Schema(description = "Why the range digest is degraded, if it is")
                String degradedReason) {}
