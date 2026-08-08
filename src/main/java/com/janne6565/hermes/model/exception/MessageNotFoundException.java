package com.janne6565.hermes.model.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class MessageNotFoundException extends BaseException {

    public MessageNotFoundException(UUID messageId) {
        super(HttpStatus.NOT_FOUND, "Message not found: " + messageId);
    }
}
