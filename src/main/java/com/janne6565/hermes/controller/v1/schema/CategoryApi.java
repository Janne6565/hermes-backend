package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.AssignCategoryRequest;
import com.janne6565.hermes.model.action.CreateCategoryRequest;
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

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a category",
            description = "Its messages return to the fallback category and its rules go with it.")
    @ApiResponse(responseCode = "204", description = "Category deleted")
    @ApiResponse(responseCode = "404", description = "No such category")
    @ApiResponse(responseCode = "409", description = "Built-in categories cannot be deleted")
    ResponseEntity<Void> delete(@PathVariable UUID id);

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
