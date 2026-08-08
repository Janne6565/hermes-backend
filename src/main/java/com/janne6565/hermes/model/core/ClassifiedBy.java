package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Which stage of the pipeline produced a message's priority. */
public enum ClassifiedBy {
    /** A hard rule matched — no LLM call was made. */
    RULE,
    /** The sidecar classified it. */
    LLM,
    /** The sidecar was unavailable; the message was parked as {@code normal} for later retry. */
    FALLBACK;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ClassifiedBy fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
