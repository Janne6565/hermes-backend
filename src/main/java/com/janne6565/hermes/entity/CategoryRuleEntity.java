package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.model.core.RuleType;
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

/**
 * "Mail matching this pattern is about X."
 *
 * <p>Structurally separate from {@link RuleEntity}: this table has no priority column, so a
 * category correction cannot reach the tier that decides what interrupts the user. The pattern
 * syntax and matching semantics are shared with the priority engine.
 */
@Entity
@Table(name = "category_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRuleEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleType type;

    @Column(nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RuleSource source = RuleSource.MANUAL;

    @Column(nullable = false)
    @Builder.Default
    private long hits = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
