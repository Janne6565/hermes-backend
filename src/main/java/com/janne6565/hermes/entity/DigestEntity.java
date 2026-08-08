package com.janne6565.hermes.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A rendered digest, persisted so the Janus widget and the history view read the same bytes that
 * were pushed to the phone rather than recomputing from mutable message rows.
 */
@Entity
@Table(name = "digests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Column(name = "digest_date", nullable = false, unique = true)
    private LocalDate date;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String content;

    /** Null if the digest was rendered but delivery failed or was skipped. */
    @Column(name = "sent_at")
    private Instant sentAt;
}
