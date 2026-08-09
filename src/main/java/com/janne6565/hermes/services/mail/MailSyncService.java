package com.janne6565.hermes.services.mail;

import com.janne6565.hermes.client.MailAccount;
import com.janne6565.hermes.client.MailProvider;
import com.janne6565.hermes.entity.AccountSyncStateEntity;
import com.janne6565.hermes.model.core.CursorPage;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.SyncResultDto;
import com.janne6565.hermes.services.auth.MailAccountService;
import com.janne6565.hermes.services.classification.ClassificationService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The polling loop, once per connected mailbox.
 *
 * <p>Incremental sync against a persisted per-account cursor; a bounded inbox listing only as a
 * cold-start or recovery path. The cursor advances only after an account's batch is processed, so a
 * crash mid-batch replays rather than skips — and {@code (provider, external_id)} is unique, so a
 * replay is a no-op.
 *
 * <p>One scheduled task iterating accounts rather than a task per account: accounts are few, and a
 * single loop is far easier to reason about at shutdown than N concurrent ones racing the same
 * flag. One account's failure is contained and does not stop the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailSyncService {

    private final MailAccountService accountService;
    private final MailProviderRegistry providers;
    private final AccountSyncStateService syncStateService;
    private final ClassificationService classificationService;

    /**
     * Set on shutdown so an in-flight batch stops at the next message boundary.
     *
     * <p>Without it the pod sat in Terminating for minutes on a cold start and was then SIGKILLed
     * before the cursor was written, so the next pod repeated the whole backfill.
     */
    private volatile boolean shuttingDown = false;

    /**
     * Serialises the scheduled loop against a user-triggered refresh.
     *
     * <p>The scheduler runs one task at a time, so before the refresh button existed nothing could
     * overlap. A manual sync runs on the request thread and can land mid-tick; both would then walk
     * the same cursor and race to write it back. Duplicates are harmless — {@code (provider,
     * external_id)} is unique — but the second writer could rewind the cursor to the value it read,
     * so the newer messages the first run just ingested would be fetched again on the next tick.
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    @PreDestroy
    void stopAcceptingWork() {
        shuttingDown = true;
    }

    @Scheduled(
            fixedDelayString = "#{@hermesProperties.gmail.pollInterval.toMillis()}",
            initialDelay = 15_000)
    public void poll() {
        if (!syncLock.tryLock()) {
            // A manual refresh is already walking the mailbox. Skipping is right rather than
            // queueing: this tick's work is exactly what that run is doing.
            log.debug("A sync is already running; skipping this scheduled tick");
            return;
        }
        try {
            syncAllAccounts();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Runs the poll immediately, on demand.
     *
     * <p>Synchronous, so the caller can refetch and actually see the new mail — an async 202 would
     * leave the UI guessing when to look again. It normally returns in well under a second: the
     * scheduled loop has kept the cursor current, so there are a handful of messages at most. The
     * exception is a cold start, where the bounded backfill can outlast the ingress timeout; the
     * sync still finishes server-side, and the next poll or refresh shows the result.
     *
     * @return what the run did, or {@code alreadyRunning} if a scheduled tick held the lock — the
     *     caller has nothing to do in that case but wait for the run in progress.
     */
    public SyncResultDto syncNow() {
        if (!syncLock.tryLock()) {
            log.debug("Manual sync requested while the scheduled poll is running");
            return new SyncResultDto(true, 0, 0, 0);
        }
        try {
            log.info("Manual sync requested");
            return syncAllAccounts();
        } finally {
            syncLock.unlock();
        }
    }

    /** One pass over every connected mailbox. Callers must hold {@link #syncLock}. */
    private SyncResultDto syncAllAccounts() {
        List<MailAccount> accounts = accountService.connected();
        if (accounts.isEmpty()) {
            log.debug("No mailbox connected; skipping poll");
            return new SyncResultDto(false, 0, 0, 0);
        }

        int ingestedTotal = 0;
        int failed = 0;
        for (MailAccount account : accounts) {
            if (shuttingDown) {
                break;
            }
            try {
                int ingested = sync(account);
                ingestedTotal += ingested;
                if (ingested > 0) {
                    log.info("Ingested {} new messages from {}", ingested, account.email());
                }
            } catch (Exception exception) {
                // Contained per account: a broken Outlook token must not stop Gmail from syncing.
                failed++;
                log.error(
                        "Sync failed for {}: {}",
                        account.email(),
                        exception.getMessage(),
                        exception);
                syncStateService.recordError(account.id(), exception.getMessage());
            }
        }
        return new SyncResultDto(false, accounts.size(), ingestedTotal, failed);
    }

    /**
     * @return how many previously-unseen messages were classified and stored for this account.
     */
    public int sync(MailAccount account) throws IOException {
        MailProvider provider = providers.forType(account.provider());
        AccountSyncStateEntity state = syncStateService.load(account.id());

        List<String> messageIds;
        String nextCursor;

        if (state.getCursor() == null) {
            // Cold start: a bounded slice of the inbox, with the cursor pinned to *now* so the
            // backfill cannot cascade into re-reading the whole mailbox on the next tick.
            log.info("No stored cursor for {} — performing a bounded cold start", account.email());
            messageIds = provider.recentInboxIds(account);
            nextCursor = provider.currentCursor(account);
        } else {
            Optional<CursorPage> page = provider.messagesSince(account, state.getCursor());
            if (page.isEmpty()) {
                // Cursor expired; drop it and let the next tick cold-start.
                syncStateService.resetCursor(account.id());
                return 0;
            }
            messageIds = page.get().addedIds();
            nextCursor = page.get().cursor();
        }

        int ingested = 0;
        int skipped = 0;
        for (String externalId : messageIds) {
            // Cooperative cancellation. The cursor only advances once the whole batch is done, so
            // an interrupted batch must leave it untouched and be retried — never half-recorded,
            // which would skip whatever was still pending.
            if (shuttingDown) {
                log.info(
                        "Shutdown requested — stopping {} after {} ingested, {} already known;"
                                + " the cursor is left unchanged and the batch will be retried",
                        account.email(),
                        ingested,
                        skipped);
                return ingested;
            }

            // Check before fetching, not inside ingest(). A message we already have costs one
            // indexed lookup instead of an API round trip plus a classifier call — which is what
            // makes an interrupted cold start cheap to repeat rather than a rerun of the batch.
            if (classificationService.alreadySeen(account.provider(), externalId)) {
                skipped++;
                continue;
            }

            try {
                FetchedMessage fetched = provider.fetch(account, externalId);
                if (classificationService.ingest(fetched).isPresent()) {
                    ingested++;
                }
            } catch (IOException exception) {
                // One unreadable message must not stall the batch — but the cursor still advances
                // past it, so log loudly enough that a systematic failure is visible.
                log.error("Failed to fetch message {}: {}", externalId, exception.getMessage());
            }
        }

        if (skipped > 0) {
            log.info("Skipped {} already-known messages for {}", skipped, account.email());
        }
        syncStateService.recordSuccess(account.id(), nextCursor);
        return ingested;
    }
}
