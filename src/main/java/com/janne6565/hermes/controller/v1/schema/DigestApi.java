package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestStatsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/digest")
@Tag(name = "Digest", description = "The daily digest, as consumed by the app and the Janus widget")
public interface DigestApi {

    @GetMapping("/today")
    @Operation(summary = "Today's digest, built live from the current state of the day")
    @ApiResponse(responseCode = "200", description = "Digest returned")
    ResponseEntity<DigestDto> today();

    @GetMapping("/stats")
    @Operation(
            summary = "Per-day counts for the week chart",
            description =
                    "Interruptions are messages that actually reached the phone, not the high"
                            + " count — shadow mode and quiet hours mean those differ.")
    @ApiResponse(responseCode = "200", description = "Per-day counts, oldest first")
    ResponseEntity<DigestStatsDto> stats(
            @Parameter(description = "How many days back, including today")
                    @RequestParam(defaultValue = "7")
                    int days);

    @GetMapping("/{date}")
    @Operation(summary = "A historical digest, read back exactly as it was delivered")
    @ApiResponse(responseCode = "200", description = "Digest returned")
    @ApiResponse(responseCode = "404", description = "No digest was recorded for that date")
    ResponseEntity<DigestDto> byDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date);
}
