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
        ClassificationMix classificationMix) {

    public record ServiceState(
            String name,
            @Schema(description = "ok | warn | bad") String state,
            String value,
            String note) {}

    /** Share of the last window's classifications by source — the honesty check on the pipeline. */
    public record ClassificationMix(long rule, long llm, long fallback) {}
}
