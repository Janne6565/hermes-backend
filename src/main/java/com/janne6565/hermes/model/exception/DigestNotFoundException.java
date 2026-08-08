package com.janne6565.hermes.model.exception;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;

public class DigestNotFoundException extends BaseException {

    public DigestNotFoundException(LocalDate date) {
        super(HttpStatus.NOT_FOUND, "No digest recorded for " + date);
    }
}
