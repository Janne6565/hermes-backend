package com.janne6565.hermes.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A topic bucket. Orthogonal to {@link com.janne6565.hermes.model.core.Priority} by design — a
 * category says what a mail is about, never whether it interrupts.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String name;

    /** Hex swatch shown as the dot next to the name. Never red — red means broken. */
    @Column(nullable = false)
    private String color;

    /** Seeded rows; the user may recolour them but not delete them. */
    @Column(nullable = false)
    @Builder.Default
    private boolean builtin = false;

    /** Exactly one row carries this: where messages land when nothing resolved them. */
    @Column(nullable = false)
    @Builder.Default
    private boolean fallback = false;

    @Column(nullable = false)
    @Builder.Default
    private int position = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
