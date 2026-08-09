package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Everything the alerts screen shows, in one read.
 *
 * <p>One payload rather than four endpoints because the screen's numbers have to agree with each
 * other: "3 apps need attention" and the list below it must be the same query, or the screen
 * contradicts itself while both halves are individually correct.
 */
@Schema(description = "The alerts screen's whole payload")
public record AlertOverviewDto(
        List<AlertEventDto> unresolved,
        @Schema(description = "Alerts that resolved themselves within the window")
                List<AlertEventDto> resolved,
        Routing routing,
        List<SourceState> sources) {

    /**
     * How the window's alerts were routed.
     *
     * <p>Only two buckets, because only two decisions exist: an alert either earned a push or was
     * held for the digest. There is no "silenced by rule" count — alert silencing is a feature
     * Hermes does not have, and a zero there would imply one it does.
     */
    @Schema(description = "What happened to the window's alerts")
    public record Routing(
            @Schema(description = "Pushed to the phone") int pushed,
            @Schema(description = "Held for the digest") int held,
            @Schema(description = "Resolved within the window") int resolved) {}

    /** Per-source intake health — whether the webhook is actually being called. */
    public record SourceState(
            AlertSource source, int eventsInWindow, Instant lastEventAt, boolean everReceived) {}
}
