package com.janne6565.hermes.model.exception;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;

public class InvalidDigestRangeException extends BaseException {

    public InvalidDigestRangeException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }

    public static InvalidDigestRangeException inverted(LocalDate from, LocalDate to) {
        return new InvalidDigestRangeException(
                "The range ends before it starts: " + from + " to " + to);
    }

    public static InvalidDigestRangeException tooLong(int days, int maximum) {
        return new InvalidDigestRangeException(
                "A range digest covers at most %d days, not %d".formatted(maximum, days));
    }

    public static InvalidDigestRangeException inTheFuture(LocalDate to, LocalDate today) {
        return new InvalidDigestRangeException(
                "The range ends on %s, after today (%s)".formatted(to, today));
    }
}
