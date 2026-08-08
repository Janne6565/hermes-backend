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

/**
 * Single-row table holding the Gmail {@code historyId} cursor. Incremental sync resumes from here;
 * losing it costs a full {@code messages.list} cold start, not correctness.
 */
@Entity
@Table(name = "sync_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncStateEntity {

    /** There is exactly one mailbox, so the row is pinned at id 1. */
    public static final int SINGLETON_ID = 1;

    @Id private Integer id;

    @Column(name = "history_id")
    private String historyId;

    @Column(name = "last_sync")
    private Instant lastSync;

    /**
     * Set when a sync attempt fails, cleared on the next success — surfaced on the health screen.
     */
    @Column(name = "last_error", length = 2000)
    private String lastError;
}
