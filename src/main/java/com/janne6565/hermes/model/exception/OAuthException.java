package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/** Anything that goes wrong during the Google connect flow. */
public class OAuthException extends BaseException {

    public OAuthException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
