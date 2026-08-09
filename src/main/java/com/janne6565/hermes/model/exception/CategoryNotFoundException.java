package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class CategoryNotFoundException extends BaseException {

    public CategoryNotFoundException(UUID categoryId) {
        super(HttpStatus.NOT_FOUND, "Category not found: " + categoryId);
    }
}
