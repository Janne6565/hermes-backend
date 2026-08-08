package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What part of a message a rule's pattern is matched against. */
public enum RuleType {
    /** Full sender address, glob-matched (e.g. {@code no-reply@meetup.com}). */
    SENDER,
    /** Sender domain, glob-matched (e.g. {@code *.uni-potsdam.de}). */
    DOMAIN,
    /** Presence of a header, optionally {@code Name: value} (e.g. {@code List-Unsubscribe}). */
    HEADER;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RuleType fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
