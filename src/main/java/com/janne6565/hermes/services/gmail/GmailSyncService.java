package com.janne6565.hermes.services.gmail;

import com.janne6565.hermes.client.GmailClient;
import com.janne6565.hermes.entity.SyncStateEntity;
import com.janne6565.hermes.services.classification.ClassificationService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The polling loop.
 *
 * <p>Incremental sync via {@code users.history.list} against a persisted cursor; a full inbox
 * listing only as a cold-start or recovery path. The cursor advances only after the batch is
 * processed, so a crash mid-batch replays rather than skips — and {@code gmail_id} is unique, so a
 * replay is a no-op.
 *
 * <p>This class holds no transaction of its own: each message is committed by {@link
 * ClassificationService#ingest}, and the cursor by {@link SyncStateService}. One poisoned message
 * therefore cannot roll back the whole batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmailSyncService {

    private final GmailClient gmailClient;
    private final SyncStateService syncStateService;
    private final ClassificationService classificationService;

    @Scheduled(
            fixedDelayString = "#{@hermesProperties.gmail.pollInterval.toMillis()}",
            initialDelay = 15_000)
    public void poll() {
        if (!gmailClient.isConnected()) {
            log.debug("No Google account connected; skipping poll");
            return;
        }
        try {
            int ingested = sync(gmailClient);
            if (ingested > 0) {
                log.info("Ingested {} new messages", ingested);
            }
        } catch (Exception exception) {
            log.error("Gmail sync failed: {}", exception.getMessage(), exception);
            syncStateService.recordError(exception.getMessage());
        }
    }

    /**
     * @return how many previously-unseen messages were classified and stored.
     */
    public int sync(GmailClient client) throws IOException {
        SyncStateEntity state = syncStateService.load();

        List<String> messageIds;
        String nextHistoryId;

        if (state.getHistoryId() == null) {
            // Cold start: take a bounded slice of the inbox and pin the cursor to *now*, so the
            // backfill can't cascade into re-reading the whole mailbox on the next tick.
            log.info("No stored historyId — performing a bounded cold start");
            messageIds = client.recentInboxMessageIds();
            nextHistoryId = client.currentHistoryId();
        } else {
            Optional<GmailClient.HistoryPage> page = client.messagesSince(state.getHistoryId());
            if (page.isEmpty()) {
                // Cursor expired; drop it and let the next tick cold-start.
                syncStateService.resetCursor();
                return 0;
            }
            messageIds = page.get().addedMessageIds();
            nextHistoryId = page.get().historyId();
        }

        int ingested = 0;
        for (String gmailId : messageIds) {
            try {
                GmailClient.FetchedMessage fetched = client.fetch(gmailId);
                if (classificationService.ingest(fetched).isPresent()) {
                    ingested++;
                }
            } catch (IOException exception) {
                // One unreadable message must not stall the batch — but the cursor still advances
                // past it, so log loudly enough that a systematic failure is visible.
                log.error("Failed to fetch message {}: {}", gmailId, exception.getMessage());
            }
        }

        syncStateService.recordSuccess(nextHistoryId);
        return ingested;
    }
}
