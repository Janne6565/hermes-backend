package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.core.HealthDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Operational state, for the health screen and the widget dot")
public interface HealthApi {

    @GetMapping
    @Operation(summary = "Status of sync, classifier, database and delivery")
    @ApiResponse(responseCode = "200", description = "Health snapshot")
    ResponseEntity<HealthDto> snapshot();
}
