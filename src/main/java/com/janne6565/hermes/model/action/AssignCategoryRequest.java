package com.janne6565.hermes.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * "This mail is about X."
 *
 * <p>Note what is missing: there is no priority field, and the handler does not touch the message's
 * priority. Correcting a category is a statement about the topic and nothing else.
 */
@Schema(description = "Recategorise one message and teach the sender rule that follows from it")
public record AssignCategoryRequest(
        @NotNull UUID messageId,
        @NotNull UUID categoryId,
        @Schema(
                        description =
                                "Write the learned rule against the whole sending domain rather"
                                        + " than the single address")
                boolean applyToDomain,
        @Schema(
                        description =
                                "Set false to correct just this message without teaching a rule",
                        defaultValue = "true")
                Boolean learn) {

    public boolean shouldLearn() {
        return learn == null || learn;
    }
}
