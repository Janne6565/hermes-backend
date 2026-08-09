package com.janne6565.hermes.services.mail;

import com.janne6565.hermes.entity.AccountSyncStateEntity;
import com.janne6565.hermes.repository.AccountSyncStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the per-account cursor rows.
 *
 * <p>Separate bean from {@link MailSyncService} on purpose: the poll loop calls these, and a
 * {@code @Transactional} method invoked from within the same bean bypasses the proxy and would run
 * outside a transaction entirely.
 */
@Service
@RequiredArgsConstructor
public class AccountSyncStateService {

    private final AccountSyncStateRepository repository;
    private final Clock clock;

    @Transactional
    public AccountSyncStateEntity load(UUID accountId) {
        return repository
                .findById(accountId)
                .orElseGet(
                        () ->
                                repository.save(
                                        new AccountSyncStateEntity(accountId, null, null, null)));
    }

    @Transactional(readOnly = true)
    public Optional<AccountSyncStateEntity> find(UUID accountId) {
        return repository.findById(accountId);
    }

    /** Advances the cursor after a batch was processed and clears any recorded failure. */
    @Transactional
    public void recordSuccess(UUID accountId, String cursor) {
        AccountSyncStateEntity state = load(accountId);
        state.setCursor(cursor);
        state.setLastSync(Instant.now(clock));
        state.setLastError(null);
        repository.save(state);
    }

    /** Drops the cursor so the next tick runs a cold start. */
    @Transactional
    public void resetCursor(UUID accountId) {
        AccountSyncStateEntity state = load(accountId);
        state.setCursor(null);
        repository.save(state);
    }

    @Transactional
    public void recordError(UUID accountId, String message) {
        AccountSyncStateEntity state = load(accountId);
        state.setLastError(message);
        repository.save(state);
    }
}
