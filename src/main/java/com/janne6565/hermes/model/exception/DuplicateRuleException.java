package com.janne6565.hermes.model.exception;

import com.janne6565.hermes.model.core.RuleType;
import org.springframework.http.HttpStatus;

public class DuplicateRuleException extends BaseException {

    public DuplicateRuleException(RuleType type, String pattern) {
        super(
                HttpStatus.CONFLICT,
                "A %s rule for '%s' already exists".formatted(type.wire(), pattern));
    }
}
