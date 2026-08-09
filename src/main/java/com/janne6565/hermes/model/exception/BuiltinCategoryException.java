package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/** The seeded buckets are part of the classifier's vocabulary; deleting one is not a UI action. */
public class BuiltinCategoryException extends BaseException {

    public BuiltinCategoryException(String name) {
        super(HttpStatus.CONFLICT, "'" + name + "' is a built-in category and cannot be deleted");
    }
}
