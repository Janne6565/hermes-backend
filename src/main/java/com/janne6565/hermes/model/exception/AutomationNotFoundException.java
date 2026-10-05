package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AutomationNotFoundException extends BaseException {

    public AutomationNotFoundException(UUID automationId) {
        super(HttpStatus.NOT_FOUND, "Automation not found: " + automationId);
    }
}
