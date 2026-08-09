package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The rolling week behind the digest's bar chart.
 *
 * <p>{@code interruptions} is the headline number and is deliberately *not* the high count: a
 * high-priority mail that arrived during shadow mode or quiet hours never reached the phone, so
 * counting it as an interruption would overstate what the service actually cost the user.
 */
@Schema(description = "Per-day counts for the digest's week chart")
public record DigestStatsDto(List<Day> days) {

    public record Day(
            LocalDate date,
            int high,
            int normal,
            int noise,
            @Schema(description = "Messages that actually reached the phone") int interruptions,
            @Schema(description = "Whether a delivered digest was stored for this day")
                    boolean hasStoredDigest) {}
}
