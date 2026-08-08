package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The digest payload — the contract the Janus dashboard widget and the digest screen both read.
 *
 * <p>{@code unclassified} and {@code degraded} are first-class fields rather than a rendering
 * detail: the whole point of the digest is that the user can trust it, so an incomplete one has to
 * say so.
 */
@Schema(description = "One day's digest, grouped by priority")
public record DigestDto(
        LocalDate date,
        Counts counts,
        List<MessageDto> high,
        List<MessageDto> normal,
        NoiseSummary noise,
        List<AlertEventDto> alerts,
        @Schema(description = "Messages still awaiting classification") int unclassified,
        @Schema(description = "True when the classifier was unavailable for part of the day")
                boolean degraded,
        @Schema(description = "Why the digest is degraded, if it is") String degradedReason,
        Instant sentAt) {

    @Schema(description = "Headline counts for the widget's collapsed state")
    public record Counts(int high, int normal, int noise) {

        public int total() {
            return high + normal + noise;
        }
    }

    @Schema(description = "Noise is counted, not listed — with a breakdown so it stays auditable")
    public record NoiseSummary(int count, List<Category> categories) {

        public record Category(String label, int count) {}
    }
}
