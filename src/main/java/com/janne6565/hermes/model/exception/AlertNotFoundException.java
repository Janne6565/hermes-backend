package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AlertNotFoundException extends BaseException {

    public AlertNotFoundException(UUID alertId) {
        super(HttpStatus.NOT_FOUND, "Alert not found: " + alertId);
    }
}
