package com.janne6565.hermes.controller;

import com.janne6565.hermes.model.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns every {@link BaseException} into an RFC 7807 problem detail. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log4xx = LoggerFactory.getLogger("hermes.http.4xx");
    private static final Logger log5xx = LoggerFactory.getLogger("hermes.http.5xx");

    @ExceptionHandler(BaseException.class)
    public ProblemDetail handle(BaseException exception) {
        if (exception.getStatus().is5xxServerError()) {
            log5xx.error("{}: {}", exception.getStatus(), exception.getDetail(), exception);
        } else {
            log4xx.warn("{}: {}", exception.getStatus(), exception.getDetail());
        }
        return ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getDetail());
    }
}
