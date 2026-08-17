package com.janne6565.hermes.services.auth;

import com.janne6565.hermes.client.MailAccount;
import com.janne6565.hermes.entity.MailAccountEntity;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.repository.AccountSyncStateRepository;
import com.janne6565.hermes.repository.MailAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns connected mailboxes, and is the only place a refresh token is decrypted.
 *
 * <p>Providers receive a {@link MailAccount} with the token already in plaintext, so no provider
 * implementation ever touches {@link TokenCipher} or the encrypted column. That keeps the number of
 * places that can mishandle an account-level credential at one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailAccountService {

    private final MailAccountRepository accountRepository;
    private final AccountSyncStateRepository syncStateRepository;
    private final TokenCipher tokenCipher;
    private final Clock clock;

    /** Every connected mailbox, oldest first so poll order is stable across restarts. */
    @Transactional(readOnly = true)
    public List<MailAccount> connected() {
        return accountRepository.findAllByOrderByConnectedAtAsc().stream()
                .map(this::toAccount)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MailAccountEntity> all() {
        return accountRepository.findAllByOrderByConnectedAtAsc();
    }

    @Transactional(readOnly = true)
    public boolean anyConnected() {
        return accountRepository.count() > 0;
    }

    @Transactional(readOnly = true)
    public Optional<MailAccountEntity> byId(UUID id) {
        return accountRepository.findById(id);
    }

    /**
     * Connects a mailbox, or re-connects one that already exists.
     *
     * <p>Upsert on (provider, email) rather than insert: re-running the consent flow for a mailbox
     * you already have should refresh the token in place, not create a second account that polls
     * the same inbox twice.
     */
    @Transactional
    public MailAccountEntity connect(
            MailProviderType provider, String email, String refreshToken, String scope) {
        MailAccountEntity account =
                accountRepository
                        .findByProviderAndEmail(provider, email)
                        .orElseGet(
                                () ->
                                        MailAccountEntity.builder()
                                                .provider(provider)
                                                .email(email)
                                                .build());

        account.setRefreshTokenEncrypted(tokenCipher.encrypt(refreshToken));
        account.setScope(scope);
        account.setConnectedAt(Instant.now(clock));
        MailAccountEntity saved = accountRepository.save(account);

        log.info("Connected {} account {}", provider.wire(), email);
        return saved;
    }

    /**
     * Forgets an account. The cursor row goes with it via {@code ON DELETE CASCADE}, so a later
     * reconnect cold-starts rather than resuming from a cursor for mail it no longer has.
     */
    @Transactional
    public void disconnect(UUID accountId) {
        accountRepository
                .findById(accountId)
                .ifPresent(
                        account -> {
                            // Child first. Explicit as well as the FK cascade, because Hibernate
                            // does not see the database-level cascade within this persistence
                            // context — but it has to run *before* the parent delete: the other
                            // order lets Postgres cascade the row away first, and Hibernate then
                            // fails its own delete with "expected row count 1 but was 0".
                            syncStateRepository.deleteById(accountId);
                            syncStateRepository.flush();
                            accountRepository.delete(account);
                            log.info(
                                    "Disconnected {} account {}",
                                    account.getProvider().wire(),
                                    account.getEmail());
                        });
    }

    private MailAccount toAccount(MailAccountEntity entity) {
        return new MailAccount(
                entity.getId(),
                entity.getProvider(),
                entity.getEmail(),
                tokenCipher.decrypt(entity.getRefreshTokenEncrypted()));
    }
}
