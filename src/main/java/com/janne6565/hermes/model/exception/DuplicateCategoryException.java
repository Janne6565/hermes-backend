package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

public class DuplicateCategoryException extends BaseException {

    public DuplicateCategoryException(String name) {
        super(HttpStatus.CONFLICT, "A category named '" + name + "' already exists");
    }
}
