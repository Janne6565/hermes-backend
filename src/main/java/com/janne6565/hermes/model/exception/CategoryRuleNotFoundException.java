package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class CategoryRuleNotFoundException extends BaseException {

    public CategoryRuleNotFoundException(UUID ruleId) {
        super(HttpStatus.NOT_FOUND, "Category rule not found: " + ruleId);
    }
}
