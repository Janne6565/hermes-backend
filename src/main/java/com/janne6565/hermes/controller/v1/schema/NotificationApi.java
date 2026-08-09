package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.core.TestPushResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "The push channel itself")
public interface NotificationApi {

    @PostMapping("/test")
    @Operation(
            summary = "Send a test push",
            description =
                    "Bypasses shadow mode and quiet hours on purpose — the point is to prove the"
                            + " channel works, and a test that silently does nothing would prove"
                            + " the opposite of what it claims.")
    @ApiResponse(
            responseCode = "200",
            description = "Whether ntfy accepted it, and how long it took")
    ResponseEntity<TestPushResultDto> sendTest();
}
