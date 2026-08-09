package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Which mail service a message or account came from. */
public enum MailProviderType {
    GMAIL,
    OUTLOOK;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static MailProviderType fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
