package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

public class DuplicateAutomationException extends BaseException {

    public DuplicateAutomationException(String name) {
        super(HttpStatus.CONFLICT, "An automation named '" + name + "' already exists");
    }
}
