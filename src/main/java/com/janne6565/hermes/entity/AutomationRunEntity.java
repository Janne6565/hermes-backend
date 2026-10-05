package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.ActionOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One firing of one automation, with what each of its actions actually did. */
@Entity
@Table(name = "automation_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationRunEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "automation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AutomationEntity automation;

    /** Null for a test run. Cascades so retention takes the run along with its mail. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MessageEntity message;

    @Column(name = "fired_at", nullable = false)
    @Builder.Default
    private Instant firedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_outcome", nullable = false)
    private ActionOutcome alertOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "webhook_outcome", nullable = false)
    private ActionOutcome webhookOutcome;

    /** Why an action failed or was held back, e.g. {@code HTTP 502} or {@code quiet hours}. */
    @Column private String detail;
}
