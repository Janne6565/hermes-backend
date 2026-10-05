package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.CreateAutomationRequest;
import com.janne6565.hermes.model.action.UpdateAutomationRequest;
import com.janne6565.hermes.model.core.AutomationDto;
import com.janne6565.hermes.model.core.AutomationOverviewDto;
import com.janne6565.hermes.model.core.AutomationRunDto;
import io.swagger.v3.oas.annotations.Operation;
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

@RequestMapping("/api/v1/automations")
@Tag(
        name = "Automations",
        description =
                "Natural-language triggers, judged by the classifier in the same turn as priority,"
                        + " that push or call a webhook. They never change a message's priority.")
public interface AutomationApi {

    @GetMapping
    @Operation(summary = "Every automation plus the most recent runs")
    @ApiResponse(responseCode = "200", description = "Overview returned")
    ResponseEntity<AutomationOverviewDto> overview();

    @PostMapping
    @Operation(
            summary = "Create an automation",
            description =
                    "Applies to mail that arrives from now on; existing mail is not re-evaluated.")
    @ApiResponse(responseCode = "201", description = "Automation created")
    @ApiResponse(responseCode = "400", description = "No alert and no webhook")
    @ApiResponse(
            responseCode = "409",
            description = "Name already taken, or the enabled limit is reached")
    ResponseEntity<AutomationDto> create(@Valid @RequestBody CreateAutomationRequest request);

    @PatchMapping("/{id}")
    @Operation(summary = "Edit, pause or resume an automation")
    @ApiResponse(responseCode = "200", description = "Automation updated")
    @ApiResponse(responseCode = "400", description = "The edit would leave no action")
    @ApiResponse(responseCode = "404", description = "No such automation")
    @ApiResponse(
            responseCode = "409",
            description = "Name already taken, or the enabled limit is reached")
    ResponseEntity<AutomationDto> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateAutomationRequest request);

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an automation and its run history")
    @ApiResponse(responseCode = "204", description = "Automation deleted")
    @ApiResponse(responseCode = "404", description = "No such automation")
    ResponseEntity<Void> delete(@PathVariable UUID id);

    @PostMapping("/{id}/test")
    @Operation(
            summary = "Run the actions once with a placeholder mail",
            description =
                    "Sends the push and calls the webhook with `test: true` and no message."
                            + " Bypasses shadow mode and quiet hours, like the settings test push,"
                            + " and does not count as a firing.")
    @ApiResponse(responseCode = "200", description = "What each action did")
    @ApiResponse(responseCode = "404", description = "No such automation")
    ResponseEntity<AutomationRunDto> test(@PathVariable UUID id);
}
