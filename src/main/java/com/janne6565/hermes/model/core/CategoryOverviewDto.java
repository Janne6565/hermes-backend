package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The whole categories screen in one read.
 *
 * <p>One endpoint rather than four, for the same reason the alerts overview is one endpoint: the
 * shares, the mix and the "needs a call" queue are three views of the same window, and fetching
 * them separately lets the screen show a mix that does not add up to the list beneath it.
 */
@Schema(description = "Category shares, the classification mix and the low-confidence queue")
public record CategoryOverviewDto(
        @Schema(description = "Length of the reporting window in days") int windowDays,
        List<CategoryDto> categories,
        Mix mix,
        List<UnsureDto> unsure,
        @Schema(description = "The user's most recent recategorisations, newest first")
                List<CorrectionDto> recentCorrections) {

    /**
     * How the window's categories were settled.
     *
     * <p>Counts, not percentages: the screen renders the share, but a percentage computed server
     * side would round independently of the numbers next to it.
     *
     * @param unresolved nothing matched and the classifier never ran — the fallback bucket.
     */
    @Schema(description = "How the window's messages got their category")
    public record Mix(int byRule, int byModel, int lowConfidence, int byUser, int unresolved) {

        public int total() {
            return byRule + byModel + lowConfidence + byUser + unresolved;
        }
    }

    /**
     * One message the classifier could not place confidently.
     *
     * @param alternative the classifier's runner-up, or null when it named only one.
     */
    @Schema(description = "A message whose category the classifier is not sure about")
    public record UnsureDto(
            UUID messageId,
            String sender,
            String subject,
            Instant receivedAt,
            @Schema(description = "Model confidence, 0..1") float confidence,
            CategoryRef guess,
            CategoryRef alternative) {}

    @Schema(description = "Just enough of a category to render a chip")
    public record CategoryRef(UUID id, String name, String color) {}

    @Schema(description = "A category the user overrode, and what it was before")
    public record CorrectionDto(
            UUID messageId,
            String subject,
            String category,
            @Schema(description = "Null when the message had no category before the correction")
                    String previousCategory,
            Instant correctedAt) {}
}
