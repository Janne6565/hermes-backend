package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Who decided a message's category.
 *
 * <p>Kept apart from {@link ClassifiedBy}, which answers the same question about priority. The two
 * genuinely differ: a message whose priority came from a hard rule never reaches the classifier, so
 * its category is whatever a category rule says — or nothing at all.
 */
public enum CategorySource {
    /** A category rule matched the sender, domain or a header. */
    RULE,
    /** The classifier chose it; {@code categoryConfidence} says how sure it was. */
    LLM,
    /** The user corrected it on the categories screen. */
    USER,
    /** Nothing resolved it — the message sits in the fallback category. */
    NONE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static CategorySource fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
