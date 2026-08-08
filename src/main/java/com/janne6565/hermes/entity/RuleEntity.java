package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.model.core.RuleType;
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

/** A hard rule, evaluated before the LLM ever sees a message. Editable at runtime. */
@Entity
@Table(name = "rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleType type;

    /** Glob pattern for sender/domain rules, header name (or {@code Name: value}) for headers. */
    @Column(nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RuleSource source = RuleSource.MANUAL;

    /** Match counter, so the rules screen can show which rules are actually earning their keep. */
    @Column(nullable = false)
    @Builder.Default
    private long hits = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
