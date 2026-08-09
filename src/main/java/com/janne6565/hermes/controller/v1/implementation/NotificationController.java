package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.NotificationApi;
import com.janne6565.hermes.model.core.TestPushResultDto;
import com.janne6565.hermes.services.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NotificationController implements NotificationApi {

    private final NotificationService notificationService;

    @Override
    public ResponseEntity<TestPushResultDto> sendTest() {
        return ResponseEntity.ok(notificationService.sendTest());
    }
}
