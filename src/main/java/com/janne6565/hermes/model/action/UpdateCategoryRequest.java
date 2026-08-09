package com.janne6565.hermes.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Rename or recolour a category. Both fields optional — send only what changes.
 *
 * <p>Renaming is deliberately cheap and non-destructive. Messages and rules reference the category
 * by id, so nothing has to be migrated, and the classifier picks the new name up on its next call
 * because the vocabulary is sent per request rather than baked into the prompt.
 */
@Schema(description = "Rename or recolour a category; omitted fields are left alone")
public record UpdateCategoryRequest(
        @Size(min = 1, max = 40) String name,
        @Schema(description = "Hex swatch, e.g. #6b8fa8") @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
                String color) {}
