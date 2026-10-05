package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What one action of one automation run actually did. */
public enum ActionOutcome {
    /** Accepted by ntfy, or the webhook answered 2xx. */
    DELIVERED,
    /** Attempted and failed — ntfy refused, or the webhook errored or timed out. */
    FAILED,
    /** Deliberately not attempted: shadow mode, quiet hours, or a late (re-classified) match. */
    SUPPRESSED,
    /** The automation has no such action configured. */
    NONE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ActionOutcome fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
