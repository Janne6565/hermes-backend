package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.MessageApi;
import com.janne6565.hermes.model.action.DismissRequest;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.services.classification.MessageQueryService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MessageController implements MessageApi {

    private final MessageQueryService messageQueryService;

    @Override
    public ResponseEntity<List<MessageDto>> search(
            Priority priority, LocalDate date, String sender, String q, int limit) {
        return ResponseEntity.ok(messageQueryService.search(priority, date, sender, q, limit));
    }

    @Override
    public ResponseEntity<List<MessageDto>> openHighPriority(int days) {
        return ResponseEntity.ok(messageQueryService.openHighPriority(days));
    }

    @Override
    public ResponseEntity<MessageDto> dismiss(UUID id, DismissRequest request) {
        boolean dismissed = request == null || request.resolved();
        return ResponseEntity.ok(messageQueryService.setDismissed(id, dismissed));
    }
}
