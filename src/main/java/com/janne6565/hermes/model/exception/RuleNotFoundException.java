package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class RuleNotFoundException extends BaseException {

    public RuleNotFoundException(UUID ruleId) {
        super(HttpStatus.NOT_FOUND, "Rule not found: " + ruleId);
    }
}
