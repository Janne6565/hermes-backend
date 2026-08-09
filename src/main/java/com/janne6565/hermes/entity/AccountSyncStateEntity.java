package com.janne6565.hermes.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Where each account's incremental sync resumes from.
 *
 * <p>Separate from {@link MailAccountEntity} on purpose: this row is written every poll, that one
 * holds a credential and is written about twice in its life. No routine write path should be able
 * to touch a refresh token.
 */
@Entity
@Table(name = "account_sync_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountSyncStateEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    /** Gmail's historyId, Graph's deltaLink — opaque to everything above the provider. */
    @Column private String cursor;

    @Column(name = "last_sync")
    private Instant lastSync;

    /** Set when a sync attempt fails, cleared on the next success. */
    @Column(name = "last_error", length = 2000)
    private String lastError;
}
