package com.janne6565.hermes.services.gmail;

import com.janne6565.hermes.entity.SyncStateEntity;
import com.janne6565.hermes.repository.SyncStateRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code sync_state} row.
 *
 * <p>Separate from {@link GmailSyncService} on purpose: the poll loop calls these methods, and a
 * {@code @Transactional} method invoked from within the same bean bypasses the proxy and would run
 * outside a transaction entirely.
 */
@Service
@RequiredArgsConstructor
public class SyncStateService {

    private final SyncStateRepository syncStateRepository;
    private final Clock clock;

    @Transactional
    public SyncStateEntity load() {
        return syncStateRepository
                .findById(SyncStateEntity.SINGLETON_ID)
                .orElseGet(
                        () ->
                                syncStateRepository.save(
                                        new SyncStateEntity(
                                                SyncStateEntity.SINGLETON_ID, null, null, null)));
    }

    @Transactional(readOnly = true)
    public SyncStateEntity current() {
        return syncStateRepository
                .findById(SyncStateEntity.SINGLETON_ID)
                .orElseGet(
                        () -> new SyncStateEntity(SyncStateEntity.SINGLETON_ID, null, null, null));
    }

    /** Advances the cursor after a batch was processed and clears any recorded failure. */
    @Transactional
    public void recordSuccess(String historyId) {
        SyncStateEntity state = load();
        state.setHistoryId(historyId);
        state.setLastSync(Instant.now(clock));
        state.setLastError(null);
        syncStateRepository.save(state);
    }

    /** Drops the cursor so the next tick runs a cold start. */
    @Transactional
    public void resetCursor() {
        SyncStateEntity state = load();
        state.setHistoryId(null);
        syncStateRepository.save(state);
    }

    @Transactional
    public void recordError(String message) {
        SyncStateEntity state = load();
        state.setLastError(message);
        syncStateRepository.save(state);
    }
}
