package com.janne6565.hermes.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Root of every exception this service raises deliberately. */
@Getter
public abstract class BaseException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;

    protected BaseException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
        this.detail = detail;
    }
}
