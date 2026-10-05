package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/** An automation with no alert and no webhook would fire and do nothing. */
public class AutomationWithoutActionException extends BaseException {

    public AutomationWithoutActionException() {
        super(HttpStatus.BAD_REQUEST, "An automation needs an alert, a webhook, or both");
    }
}
