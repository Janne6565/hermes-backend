package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.DigestApi;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestStatsDto;
import com.janne6565.hermes.services.digest.DigestService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DigestController implements DigestApi {

    private final DigestService digestService;

    @Override
    public ResponseEntity<DigestDto> today() {
        return ResponseEntity.ok(digestService.today());
    }

    @Override
    public ResponseEntity<DigestStatsDto> stats(int days) {
        return ResponseEntity.ok(digestService.stats(days));
    }

    @Override
    public ResponseEntity<DigestDto> byDate(LocalDate date) {
        return ResponseEntity.ok(digestService.forDate(date));
    }
}
