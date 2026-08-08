package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Monitoring system a webhook alert came from. */
public enum AlertSource {
    GRAFANA,
    SIGNOZ;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AlertSource fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
