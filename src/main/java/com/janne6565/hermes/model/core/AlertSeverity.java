package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Normalised alert severity. Grafana and SigNoz spell these differently in their payloads; {@link
 * #fromWire} absorbs the variants so the routing table stays small.
 */
public enum AlertSeverity {
    CRITICAL,
    ERROR,
    WARNING,
    INFO,
    RESOLVED;

    /** Severities that warrant an immediate push; everything else waits for the digest. */
    public boolean warrantsPush() {
        return this == CRITICAL || this == ERROR;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AlertSeverity fromWire(String value) {
        if (value == null || value.isBlank()) {
            return INFO;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "crit", "page", "p1", "fatal" -> CRITICAL;
            case "error", "err", "firing" -> ERROR;
            case "warning", "warn", "p2" -> WARNING;
            case "resolved", "ok", "normal" -> RESOLVED;
            default -> INFO;
        };
    }
}
