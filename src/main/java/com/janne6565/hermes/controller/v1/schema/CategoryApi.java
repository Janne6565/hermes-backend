package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.AssignCategoryRequest;
import com.janne6565.hermes.model.action.CreateCategoryRequest;
import com.janne6565.hermes.model.action.UpdateCategoryRequest;
import com.janne6565.hermes.model.core.BackfillStatusDto;
import com.janne6565.hermes.model.core.CategoryDto;
import com.janne6565.hermes.model.core.CategoryOverviewDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/categories")
@Tag(
        name = "Categories",
        description = "Topic buckets. Every message gets exactly one; priority stays separate.")
public interface CategoryApi {

    @GetMapping
    @Operation(
            summary = "The whole categories screen",
            description =
                    "Shares over the window, how each category was settled, the low-confidence"
                            + " queue and the user's recent corrections — one read, so the numbers"
                            + " cannot contradict the lists beneath them.")
    @ApiResponse(responseCode = "200", description = "Overview returned")
    ResponseEntity<CategoryOverviewDto> overview(
            @Parameter(description = "Reporting window; defaults to the configured value")
                    @RequestParam(required = false)
                    Integer days);

    @PostMapping
    @Operation(summary = "Create a category")
    @ApiResponse(responseCode = "201", description = "Category created")
    @ApiResponse(responseCode = "409", description = "A category with that name already exists")
    ResponseEntity<CategoryDto> create(@Valid @RequestBody CreateCategoryRequest request);

    @PatchMapping("/{id}")
    @Operation(
            summary = "Rename or recolour a category",
            description =
                    "Built-ins are renameable even though they cannot be deleted: deleting one"
                            + " shrinks the classifier's vocabulary, renaming only changes the label"
                            + " it answers with. Messages keep their id-based link, so nothing is"
                            + " migrated and the next classification already uses the new name.")
    @ApiResponse(responseCode = "200", description = "Category updated")
    @ApiResponse(responseCode = "404", description = "No such category")
    @ApiResponse(responseCode = "409", description = "Another category already has that name")
    ResponseEntity<CategoryDto> rename(
            @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request);

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a category",
            description = "Its messages return to the fallback category and its rules go with it.")
    @ApiResponse(responseCode = "204", description = "Category deleted")
    @ApiResponse(responseCode = "404", description = "No such category")
    @ApiResponse(responseCode = "409", description = "Built-in categories cannot be deleted")
    ResponseEntity<Void> delete(@PathVariable UUID id);

    @PostMapping("/backfill")
    @Operation(
            summary = "Categorise mail that predates the feature",
            description =
                    "Starts a background run and returns immediately — the work takes one Haiku"
                            + " turn per message and must not be held open by an HTTP request. Category"
                            + " rules run first and are free; the classifier handles what they miss,"
                            + " bounded by `limit`, committing each message as it goes. Priorities are"
                            + " never re-decided. Poll GET /api/v1/categories for progress. A second"
                            + " call while a run is in flight is a no-op that reports the running"
                            + " state.")
    @ApiResponse(responseCode = "200", description = "The state of the run that was just started")
    ResponseEntity<BackfillStatusDto> backfill(
            @Parameter(description = "Cap on classifier calls in this run")
                    @RequestParam(defaultValue = "200")
                    int limit);

    @DeleteMapping("/rules/{ruleId}")
    @Operation(
            summary = "Remove one pattern from a category",
            description =
                    "Mail already filed by this rule keeps its category — the rule explains how a"
                            + " message got there, not where it belongs, and re-opening settled mail"
                            + " would be a far larger action than this button implies.")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @ApiResponse(responseCode = "404", description = "No such rule")
    ResponseEntity<Void> deleteRule(@PathVariable UUID ruleId);

    @PostMapping("/assign")
    @Operation(
            summary = "\"This mail is about X\"",
            description =
                    "Recategorises the message and, unless learning is switched off, writes the"
                            + " sender or domain rule that follows. The message's priority is not"
                            + " touched.")
    @ApiResponse(responseCode = "200", description = "Message recategorised")
    @ApiResponse(responseCode = "404", description = "No such message or category")
    ResponseEntity<CategoryDto> assign(@Valid @RequestBody AssignCategoryRequest request);
}
