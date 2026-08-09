package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The outcome of one backfill run.
 *
 * <p>{@code skipped} and {@code remaining} are separate numbers because they mean different things
 * to whoever presses the button again: skipped messages were offered to the classifier and it had
 * no answer, remaining ones were never reached. Collapsing them would make a second run look
 * pointless when it is not.
 */
@Schema(description = "What one categorisation backfill run did")
public record CategoryBackfillDto(
        @Schema(description = "Messages without a resolved category when the run started")
                int candidates,
        @Schema(description = "Settled for free by a category rule") int categorisedByRule,
        @Schema(description = "Settled by the classifier") int categorisedByModel,
        @Schema(description = "Offered to the classifier, which named nothing usable") int skipped,
        @Schema(description = "Never reached; run again to continue") int remaining,
        @Schema(
                        description =
                                "Why the run stopped early: \"limit\" or \"sidecar_unavailable\","
                                        + " or null if it finished")
                String stoppedBecause) {}
