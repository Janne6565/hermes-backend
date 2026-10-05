package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What, if anything, an automation pushes to the phone when it fires. */
public enum AutomationAlert {
    /** No push — the automation only calls its webhook. */
    NONE,
    /** A push now instead of waiting for the digest. Held during quiet hours like any mail. */
    DIRECT,
    /**
     * An urgent push that overrides Do Not Disturb and pierces quiet hours. The user wrote this
     * trigger themselves and asked to be woken for it, which is the one thing mail otherwise never
     * gets to do.
     */
    IMPORTANT;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AutomationAlert fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
