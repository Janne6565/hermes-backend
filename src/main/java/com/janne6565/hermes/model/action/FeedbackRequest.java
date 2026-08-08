package com.janne6565.hermes.model.action;

import com.janne6565.hermes.model.core.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * "This shouldn't have pinged me." Creates a sender rule from the offending message so the same
 * sender is silenced next time — one tap, no rule editor.
 */
@Schema(description = "Correct a misclassification and auto-create the matching sender rule")
public record FeedbackRequest(
        @NotNull UUID messageId,
        @Schema(description = "What the priority should have been") @NotNull Priority shouldHaveBeen,
        @Schema(
                        description =
                                "Create a domain rule instead of a sender rule — use for whole newsletter domains")
                boolean applyToDomain) {}
