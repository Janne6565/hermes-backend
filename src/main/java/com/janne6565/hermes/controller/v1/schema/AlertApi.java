package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.AlertWebhookRequest;
import com.janne6565.hermes.model.core.AlertEventDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

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
}
