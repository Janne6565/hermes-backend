package com.janne6565.hermes.services.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.MailProvider;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.entity.SyncStateEntity;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.services.classification.ClassificationService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The cold-start path is the expensive one — up to 100 messages, each a Gmail fetch plus a
 * classifier round trip. These cover the two properties that keep an interrupted cold start from
 * repeating that work or corrupting the cursor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GmailSyncServiceTest {

    @Mock private MailProvider gmailClient;
    @Mock private SyncStateService syncStateService;
    @Mock private ClassificationService classificationService;

    @InjectMocks private GmailSyncService gmailSyncService;

    @BeforeEach
    void coldStart() throws IOException {
        // No stored cursor -> the cold-start branch.
        when(syncStateService.load())
                .thenReturn(new SyncStateEntity(SyncStateEntity.SINGLETON_ID, null, null, null));
        when(gmailClient.currentCursor()).thenReturn("999");
    }

    @Test
    void skipsKnownMessagesWithoutFetchingThem() throws IOException {
        when(gmailClient.recentInboxIds()).thenReturn(List.of("a", "b", "c"));
        when(classificationService.alreadySeen("a")).thenReturn(true);
        when(classificationService.alreadySeen("b")).thenReturn(true);
        when(classificationService.alreadySeen("c")).thenReturn(false);
        when(gmailClient.fetch("c")).thenReturn(fetched("c"));
        when(classificationService.ingest(any())).thenReturn(Optional.of(new MessageEntity()));

        int ingested = gmailSyncService.sync(gmailClient);

        assertThat(ingested).isEqualTo(1);
        // The whole point: a repeated cold start must not re-fetch what we already have.
        verify(gmailClient, never()).fetch("a");
        verify(gmailClient, never()).fetch("b");
        verify(gmailClient).fetch("c");
        verify(syncStateService).recordSuccess("999");
    }

    @Test
    void leavesTheCursorAloneWhenInterrupted() throws IOException {
        when(gmailClient.recentInboxIds()).thenReturn(List.of("a", "b"));
        gmailSyncService.stopAcceptingWork();

        int ingested = gmailSyncService.sync(gmailClient);

        assertThat(ingested).isZero();
        // Recording a cursor here would skip every message still pending in this batch.
        verify(syncStateService, never()).recordSuccess(anyString());
        verify(gmailClient, never()).fetch(anyString());
    }

    private static FetchedMessage fetched(String id) {
        return new FetchedMessage(
                MailProviderType.GMAIL,
                id,
                "a@b.c",
                "subject",
                "snippet",
                java.time.Instant.EPOCH,
                java.util.Map.of());
    }
}
