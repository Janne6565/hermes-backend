package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The state of the categorisation backfill.
 *
 * <p>A state rather than a result: the run is detached from the request that starts it, so there is
 * nothing to report at the moment of asking. The screen polls this and watches {@code uncategorised
 * } fall.
 */
@Schema(description = "Whether a categorisation backfill is running, and how far it has got")
public record BackfillStatusDto(
        boolean running,
        @Schema(description = "Messages the classifier has answered for in this run") int processed,
        @Schema(description = "How many this run intends to reach; 0 until the rule pass is done")
                int target,
        @Schema(description = "Messages still without a resolved category, right now")
                int uncategorised,
        @Schema(
                        description =
                                "How the last run ended: \"done\", \"more_remaining\","
                                        + " \"sidecar_unavailable\" or \"failed\"; null if none has"
                                        + " run since startup")
                String lastOutcome) {}
