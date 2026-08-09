package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.controller.v1.schema.RuleApi;
import com.janne6565.hermes.model.action.CreateRuleRequest;
import com.janne6565.hermes.model.action.FeedbackRequest;
import com.janne6565.hermes.model.core.RuleDryRunDto;
import com.janne6565.hermes.model.core.RuleDto;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.services.rules.RuleService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuleController implements RuleApi {

    private final RuleService ruleService;

    @Override
    public ResponseEntity<List<RuleDto>> list() {
        return ResponseEntity.ok(ruleService.list());
    }

    @Override
    public ResponseEntity<RuleDto> create(CreateRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(request));
    }

    @Override
    public ResponseEntity<RuleDryRunDto> dryRun(RuleType type, String pattern, int sampleSize) {
        return ResponseEntity.ok(ruleService.dryRun(type, pattern, sampleSize));
    }

    @Override
    public ResponseEntity<List<RuleDto>> recentFeedback(int limit) {
        return ResponseEntity.ok(ruleService.recentFeedback(limit));
    }

    @Override
    public ResponseEntity<RuleDto> setEnabled(UUID id, boolean enabled) {
        return ResponseEntity.ok(ruleService.setEnabled(id, enabled));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        ruleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RuleDto> feedback(FeedbackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.applyFeedback(request));
    }
}
