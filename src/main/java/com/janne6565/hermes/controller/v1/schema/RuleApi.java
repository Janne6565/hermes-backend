package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.CreateRuleRequest;
import com.janne6565.hermes.model.action.FeedbackRequest;
import com.janne6565.hermes.model.core.RuleDryRunDto;
import com.janne6565.hermes.model.core.RuleDto;
import com.janne6565.hermes.model.core.RuleType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/rules")
@Tag(name = "Rules", description = "Hard rules, evaluated before the classifier")
public interface RuleApi {

    @GetMapping
    @Operation(summary = "List all rules, newest first")
    @ApiResponse(responseCode = "200", description = "Rules returned")
    ResponseEntity<List<RuleDto>> list();

    @PostMapping
    @Operation(summary = "Create a rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @ApiResponse(responseCode = "409", description = "An identical rule already exists")
    ResponseEntity<RuleDto> create(@Valid @RequestBody CreateRuleRequest request);

    @GetMapping("/dry-run")
    @Operation(
            summary = "What a candidate rule would have done",
            description =
                    "Evaluates the pattern against recent stored mail so the consequence is visible"
                            + " before the rule exists. Header rules are unsupported — headers are"
                            + " not retained after classification.")
    @ApiResponse(responseCode = "200", description = "Retrospective match counts")
    ResponseEntity<RuleDryRunDto> dryRun(
            @RequestParam RuleType type,
            @RequestParam String pattern,
            @Parameter(description = "How many recent messages to evaluate against")
                    @RequestParam(defaultValue = "500")
                    int sampleSize);

    @GetMapping("/feedback/recent")
    @Operation(summary = "Rules the user's own corrections produced, newest first")
    @ApiResponse(responseCode = "200", description = "Recent feedback-derived rules")
    ResponseEntity<List<RuleDto>> recentFeedback(@RequestParam(defaultValue = "5") int limit);

    @PostMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable a rule without deleting it")
    @ApiResponse(responseCode = "200", description = "Rule updated")
    @ApiResponse(responseCode = "404", description = "No such rule")
    ResponseEntity<RuleDto> setEnabled(@PathVariable UUID id, @RequestParam boolean enabled);

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a rule")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @ApiResponse(responseCode = "404", description = "No such rule")
    ResponseEntity<Void> delete(@PathVariable UUID id);

    @PostMapping("/feedback")
    @Operation(
            summary = "\"This shouldn't have pinged me\"",
            description =
                    "Corrects the message's priority and creates the matching sender or domain rule.")
    @ApiResponse(responseCode = "201", description = "Rule created or updated from the correction")
    @ApiResponse(responseCode = "404", description = "No such message")
    ResponseEntity<RuleDto> feedback(@Valid @RequestBody FeedbackRequest request);
}
