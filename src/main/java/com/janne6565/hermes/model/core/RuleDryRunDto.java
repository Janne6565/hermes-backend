package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a candidate rule would have done to the mail already on record.
 *
 * <p>{@code supported} is false for header rules: message headers are not retained after
 * classification, so there is nothing to re-evaluate them against. Reporting that honestly beats
 * returning a zero that reads as "this rule would never match".
 */
@Schema(description = "Retrospective effect of a candidate rule")
public record RuleDryRunDto(
        boolean supported,
        @Schema(description = "Why the dry run could not run, when it could not") String reason,
        @Schema(description = "Messages considered") int sampleSize,
        @Schema(description = "How many the pattern would have matched") int matched,
        @Schema(description = "Of the matches, how many were classified high") int matchedHigh) {

    public static RuleDryRunDto unsupported(String reason) {
        return new RuleDryRunDto(false, reason, 0, 0, 0);
    }
}
