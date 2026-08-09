package com.janne6565.hermes.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A new topic bucket. No priority field — categories never set priority.")
public record CreateCategoryRequest(
        @NotBlank @Size(max = 40) String name,
        @Schema(description = "Hex swatch, e.g. #6b8fa8", example = "#6b8fa8")
                @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
                String color) {}
