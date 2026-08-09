package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.AlertSeverity;
import com.janne6565.hermes.model.core.AlertSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An alert received over the webhook intake. This is the authoritative path for Grafana and SigNoz
 * — the mail-based sender rules are only a safety net.
 */
@Entity
@Table(name = "alert_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEventEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity severity;

    @Column(nullable = false)
    private String title;

    /** The app/service the alert is about — drives the "apps that paged" grouping. */
    @Column(name = "app_name")
    private String appName;

    /** Deduplication key so a re-fired alert updates the existing row instead of piling up. */
    @Column(name = "fingerprint")
    private String fingerprint;

    /**
     * The raw webhook body, kept verbatim for debugging without re-plumbing the sender. Held as the
     * serialised JSON string and bound as {@code jsonb} — Hibernate maps the column type, the
     * service owns (de)serialisation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();

    /** Null while the alert is still firing; set when the source reports it resolved. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean notified = false;

    /**
     * When the operator said "I have seen this".
     *
     * <p>Deliberately not the same as {@link #resolvedAt}: only the alert source may declare an
     * alert resolved. Acknowledging settles it on screen without claiming the problem went away.
     */
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** Suppresses further pushes for this fingerprint until the given instant. */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;
}
