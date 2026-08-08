package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Triage outcome for a message or alert. Stored uppercase in Postgres, exposed lowercase over the
 * API so the frontend and the ntfy payloads read naturally.
 */
public enum Priority {
    /** Interrupt the user now: push to ntfy at urgent priority. */
    HIGH,
    /** No interruption; appears in the evening digest with its one-line summary. */
    NORMAL,
    /** Digest footnote only — counted, never listed by default. */
    NOISE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Priority fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
