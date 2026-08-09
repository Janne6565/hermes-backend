package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Powers the health screen and the widget's traffic-light dot. */
@Schema(description = "Operational state of every moving part")
public record HealthDto(
        @Schema(description = "ok | degraded | broken") String status,
        List<ServiceState> services,
        Instant lastSync,
        String historyId,
        String syncError,
        @Schema(description = "Messages parked in the fallback state") long fallbackCount,
        ClassificationMix classificationMix,
        @Schema(description = "Presence and validity of each credential — never a value")
                List<CredentialState> credentials,
        @Schema(description = "What shadow mode has seen so far") Shadow shadow) {

    /**
     * Shadow-mode progress.
     *
     * <p>There is no countdown here on purpose: shadow mode is a boolean in the ConfigMap with no
     * configured end date, so "3 days remaining" would be a number the service invented. What it
     * can honestly report is how much evidence has accumulated for the decision to go live.
     */
    public record Shadow(boolean enabled, long classified, long high, long corrections) {}

    public record ServiceState(
            String name,
            @Schema(description = "ok | warn | bad") String state,
            String value,
            String note) {}

    /**
     * One credential row.
     *
     * <p>{@code detail} is free text because the interesting fact differs per credential — an
     * expiry for one, a plain "configured" for another. {@code unknown} is a first-class state: a
     * credential we could not ask about must not render as either valid or broken.
     */
    public record CredentialState(
            String name,
            @Schema(description = "ok | warn | bad | unknown") String state,
            String detail) {}

    /** Share of the last window's classifications by source — the honesty check on the pipeline. */
    public record ClassificationMix(long rule, long llm, long fallback) {}
}
