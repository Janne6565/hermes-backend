package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/** Missing or wrong admin token on a protected endpoint. */
public class UnauthorizedException extends BaseException {

    public UnauthorizedException() {
        super(HttpStatus.UNAUTHORIZED, "Missing or invalid admin token");
    }
}
