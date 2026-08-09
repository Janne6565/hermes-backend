package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.AlertWebhookRequest;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.AlertOverviewDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/events")
@Tag(name = "Alerts", description = "Webhook intake for Grafana and SigNoz")
public interface AlertApi {

    @PostMapping("/alert")
    @Operation(
            summary = "Receive an alert",
            description =
                    "The authoritative alert path. Critical and error severities push immediately; "
                            + "warning and info are held for the digest.")
    @ApiResponse(responseCode = "202", description = "Alert recorded")
    @ApiResponse(responseCode = "401", description = "Missing or wrong shared secret")
    ResponseEntity<AlertEventDto> receive(
            @Parameter(description = "Shared secret", required = true)
                    @RequestHeader(value = "X-Hermes-Token", required = false)
                    String token,
            @Valid @RequestBody AlertWebhookRequest request);

    @GetMapping("/alerts")
    @Operation(summary = "Alerts that are still firing")
    @ApiResponse(responseCode = "200", description = "Unresolved alerts, newest first")
    ResponseEntity<List<AlertEventDto>> unresolved();

    @GetMapping("/alerts/overview")
    @Operation(
            summary = "The alerts screen in one read",
            description =
                    "Unresolved and resolved alerts over the window, how they were routed, and"
                            + " whether each webhook source is being called at all.")
    @ApiResponse(responseCode = "200", description = "Alert overview")
    ResponseEntity<AlertOverviewDto> overview(
            @Parameter(description = "Window in days, ending now") @RequestParam(defaultValue = "1")
                    int days);

    @PostMapping("/alerts/{id}/acknowledge")
    @Operation(
            summary = "Mark an alert as seen",
            description =
                    "Does not resolve it — only the alert source may decide the problem is over.")
    @ApiResponse(responseCode = "200", description = "Alert acknowledged")
    @ApiResponse(responseCode = "404", description = "No such alert")
    ResponseEntity<AlertEventDto> acknowledge(@PathVariable UUID id);

    @PostMapping("/alerts/{id}/snooze")
    @Operation(
            summary = "Stop pushing this alert for a while",
            description =
                    "Silences the phone for this alert's fingerprint. The alert stays visible —"
                            + " snoozing is not hiding.")
    @ApiResponse(responseCode = "200", description = "Alert snoozed")
    @ApiResponse(responseCode = "404", description = "No such alert")
    ResponseEntity<AlertEventDto> snooze(
            @PathVariable UUID id,
            @Parameter(description = "Hours to stay quiet; falls back to the configured default")
                    @RequestParam(required = false)
                    Integer hours);
}
