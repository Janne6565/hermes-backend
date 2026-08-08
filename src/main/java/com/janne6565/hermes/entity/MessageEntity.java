package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.Priority;
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

/** A Gmail message we have seen, with the triage verdict attached. */
@Entity
@Table(name = "messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    /** Gmail's own message id — the idempotency key for the polling loop. */
    @Column(name = "gmail_id", nullable = false, unique = true)
    private String gmailId;

    @Column(nullable = false)
    private String sender;

    @Column private String subject;

    /** First ~500 plaintext characters. This is the only body content we ever store or send. */
    @Column(length = 1000)
    private String snippet;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    /** One-line summary from the classifier; the digest renders this without a second LLM pass. */
    @Column private String summary;

    /** Why the classifier landed on this priority — shown as the reason line in the UI. */
    @Column private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "classified_by", nullable = false)
    private ClassifiedBy classifiedBy;

    /** Set when a push actually went out; null for everything that stayed silent. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /** Cleared from the "high — open" list by the user; the mail itself is untouched in Gmail. */
    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
