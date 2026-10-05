package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.AutomationApi;
import com.janne6565.hermes.model.action.CreateAutomationRequest;
import com.janne6565.hermes.model.action.UpdateAutomationRequest;
import com.janne6565.hermes.model.core.AutomationDto;
import com.janne6565.hermes.model.core.AutomationOverviewDto;
import com.janne6565.hermes.model.core.AutomationRunDto;
import com.janne6565.hermes.services.automations.AutomationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AutomationController implements AutomationApi {

    private final AutomationService automationService;

    @Override
    public ResponseEntity<AutomationOverviewDto> overview() {
        return ResponseEntity.ok(automationService.overview());
    }

    @Override
    public ResponseEntity<AutomationDto> create(CreateAutomationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(automationService.create(request));
    }

    @Override
    public ResponseEntity<AutomationDto> update(UUID id, UpdateAutomationRequest request) {
        return ResponseEntity.ok(automationService.update(id, request));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        automationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AutomationRunDto> test(UUID id) {
        return ResponseEntity.ok(automationService.test(id));
    }
}
