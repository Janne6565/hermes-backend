package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.MailProviderType;
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

/** A connected mailbox. Replaces the single pinned google_account row. */
@Entity
@Table(name = "mail_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailAccountEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MailProviderType provider;

    /** Shown in the UI so it is obvious *which* mailbox is connected. */
    @Column private String email;

    /** Base64 of nonce || AES-GCM ciphertext. Never logged, never leaves the service. */
    @Column(name = "refresh_token_encrypted", nullable = false)
    private String refreshTokenEncrypted;

    @Column(nullable = false)
    private String scope;

    @Column(name = "connected_at", nullable = false)
    @Builder.Default
    private Instant connectedAt = Instant.now();

    /** Keeps the ciphertext out of logs and stack traces. */
    @Override
    public String toString() {
        return "MailAccountEntity(id=%s, provider=%s, email=%s)".formatted(id, provider, email);
    }
}
