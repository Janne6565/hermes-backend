package com.janne6565.hermes.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The single connected Google account. */
@Entity
@Table(name = "google_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAccountEntity {

    /** One mailbox, one row. */
    public static final int SINGLETON_ID = 1;

    @Id private Integer id;

    /** Shown in the UI so it is obvious *which* account is connected. */
    @Column private String email;

    /** Base64 of nonce || AES-GCM ciphertext. Never logged, never leaves the service. */
    @Column(name = "refresh_token_encrypted", nullable = false)
    private String refreshTokenEncrypted;

    @Column(nullable = false)
    private String scope;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;
}
