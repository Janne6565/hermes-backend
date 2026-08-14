package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.DigestApi;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestRangeDto;
import com.janne6565.hermes.model.core.DigestStatsDto;
import com.janne6565.hermes.services.digest.DigestRangeService;
import com.janne6565.hermes.services.digest.DigestSender;
import com.janne6565.hermes.services.digest.DigestService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DigestController implements DigestApi {

    private final DigestService digestService;
    private final DigestSender digestSender;
    private final DigestRangeService digestRangeService;

    @Override
    public ResponseEntity<DigestDto> today() {
        return ResponseEntity.ok(digestService.today());
    }

    @Override
    public ResponseEntity<DigestDto> sendNow() {
        return ResponseEntity.ok(digestSender.sendNow());
    }

    @Override
    public ResponseEntity<DigestStatsDto> stats(int days) {
        return ResponseEntity.ok(digestService.stats(days));
    }

    @Override
    public ResponseEntity<DigestRangeDto> range(LocalDate from, LocalDate to) {
        return ResponseEntity.ok(digestRangeService.create(from, to));
    }

    @Override
    public ResponseEntity<DigestDto> byDate(LocalDate date) {
        return ResponseEntity.ok(digestService.forDate(date));
    }
}
