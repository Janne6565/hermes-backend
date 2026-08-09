package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.core.ConfigDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/config")
@Tag(
        name = "Config",
        description = "The effective server configuration the settings screen mirrors")
public interface ConfigApi {

    @GetMapping
    @Operation(
            summary = "Read the effective configuration",
            description =
                    "Read-only by design: these values come from the ConfigMap, so changing them is"
                            + " a reviewed commit rather than a click.")
    @ApiResponse(responseCode = "200", description = "Configuration as the service booted with it")
    ResponseEntity<ConfigDto> current();
}
