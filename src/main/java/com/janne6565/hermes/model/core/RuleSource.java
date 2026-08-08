package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Whether a rule was written by hand or generated from a "this shouldn't have pinged me" tap. */
public enum RuleSource {
    MANUAL,
    FEEDBACK;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RuleSource fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
