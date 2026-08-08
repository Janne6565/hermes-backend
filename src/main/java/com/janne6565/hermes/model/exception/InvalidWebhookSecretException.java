package com.janne6565.hermes.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The alert webhook is the one endpoint reachable from outside the cluster, so a bad or missing
 * shared secret is a 401 and nothing more is disclosed.
 */
public class InvalidWebhookSecretException extends BaseException {

    public InvalidWebhookSecretException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid or missing alert webhook secret");
    }
}
