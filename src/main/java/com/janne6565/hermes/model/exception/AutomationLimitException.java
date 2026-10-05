package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Every enabled trigger rides along on every classification, and the sidecar drops anything past
 * its bound — so the cap is enforced here, loudly, rather than there, silently.
 */
public class AutomationLimitException extends BaseException {

    public AutomationLimitException(int limit) {
        super(HttpStatus.CONFLICT, "At most " + limit + " automations can be enabled at once");
    }
}
