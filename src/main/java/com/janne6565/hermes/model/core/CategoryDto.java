package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.CategoryEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "A topic bucket, with what it caught over the reporting window")
public record CategoryDto(
        UUID id,
        String name,
        String color,
        @Schema(description = "Seeded categories cannot be deleted") boolean builtin,
        @Schema(description = "Where unresolved messages land; exactly one category has this")
                boolean fallback,
        @Schema(description = "Messages in this category over the window") int count,
        @Schema(description = "Share of the window's messages, 0..1") double share,
        @Schema(
                        description =
                                "The priority this category's mail most often ended up with —"
                                    + " observed, not configured. Categories never set priority.")
                Priority typicalPriority,
        @Schema(
                        description =
                                "The rules that file mail into this category, oldest first. The"
                                    + " table renders their patterns; the detail view lets you"
                                    + " remove one.")
                List<CategoryRuleDto> rules,
        @Schema(description = "Messages the user moved into this category during the window")
                int corrected) {

    /** The empty-window shape, so a category with no traffic still renders as a row. */
    public static CategoryDto empty(CategoryEntity entity, List<CategoryRuleDto> rules) {
        return new CategoryDto(
                entity.getId(),
                entity.getName(),
                entity.getColor(),
                entity.isBuiltin(),
                entity.isFallback(),
                0,
                0d,
                null,
                rules,
                0);
    }
}
