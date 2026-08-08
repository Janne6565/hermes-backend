package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.HealthApi;
import com.janne6565.hermes.model.core.HealthDto;
import com.janne6565.hermes.services.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController implements HealthApi {

    private final HealthService healthService;

    @Override
    public ResponseEntity<HealthDto> snapshot() {
        return ResponseEntity.ok(healthService.snapshot());
    }
}
