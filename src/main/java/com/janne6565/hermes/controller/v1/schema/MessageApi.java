package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.action.DismissRequest;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.SyncResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/messages")
@Tag(name = "Messages", description = "Browse and search classified mail")
public interface MessageApi {

    @GetMapping
    @Operation(
            summary = "Search the local index",
            description =
                    "Runs against Postgres rather than Gmail, so it keeps working while sync is down.")
    @ApiResponse(responseCode = "200", description = "Matching messages, newest first")
    ResponseEntity<List<MessageDto>> search(
            @Parameter(description = "Restrict to one priority tier")
                    @RequestParam(required = false)
                    Priority priority,
            @Parameter(description = "Restrict to a single day, in the configured timezone")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @Parameter(description = "Inclusive lower bound on the received date")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate after,
            @Parameter(description = "Exclusive upper bound on the received date")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate before,
            @Parameter(description = "Substring match on the sender")
                    @RequestParam(required = false)
                    String sender,
            @Parameter(description = "Which stage of the pipeline decided the priority")
                    @RequestParam(required = false)
                    ClassifiedBy classifiedBy,
            @Parameter(description = "Free text over subject, snippet and summary")
                    @RequestParam(required = false)
                    String q,
            @RequestParam(defaultValue = "100") int limit);

    @GetMapping("/{id}")
    @Operation(
            summary = "Read one message",
            description =
                    "The reader pane's own source, so a message stays linkable after it has dropped"
                            + " out of today's digest.")
    @ApiResponse(responseCode = "200", description = "The message")
    @ApiResponse(responseCode = "404", description = "No such message")
    ResponseEntity<MessageDto> byId(@PathVariable UUID id);

    @GetMapping("/high/open")
    @Operation(summary = "High-priority items that have not been dismissed yet")
    @ApiResponse(responseCode = "200", description = "Open high-priority messages")
    ResponseEntity<List<MessageDto>> openHighPriority(@RequestParam(defaultValue = "7") int days);

    @PostMapping("/sync")
    @Operation(
            summary = "Poll the connected mailboxes now",
            description =
                    "Runs the same sync as the scheduled poll, without waiting for the next tick."
                            + " Returns once the run is finished, so the caller can reload the list"
                            + " immediately. If a poll is already in flight the request is a no-op"
                            + " and says so.")
    @ApiResponse(responseCode = "200", description = "What the run ingested")
    ResponseEntity<SyncResultDto> sync();

    @PostMapping("/{id}/dismiss")
    @Operation(
            summary = "Dismiss a high-priority item",
            description = "Clears it from the open list. The mail itself is untouched in Gmail.")
    @ApiResponse(responseCode = "200", description = "Message updated")
    @ApiResponse(responseCode = "404", description = "No such message")
    ResponseEntity<MessageDto> dismiss(
            @PathVariable UUID id, @Valid @RequestBody(required = false) DismissRequest request);
}
