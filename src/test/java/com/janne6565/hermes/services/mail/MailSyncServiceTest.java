package com.janne6565.hermes.services.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.MailAccount;
import com.janne6565.hermes.client.MailProvider;
import com.janne6565.hermes.entity.AccountSyncStateEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.SyncResultDto;
import com.janne6565.hermes.services.auth.MailAccountService;
import com.janne6565.hermes.services.classification.ClassificationService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The cold-start path is the expensive one — up to 100 messages, each an API fetch plus a
 * classifier round trip. These cover the properties that keep an interrupted cold start from
 * repeating that work or corrupting the cursor, and that one account's failure cannot stop
 * another's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailSyncServiceTest {

    private static final MailAccount GMAIL =
            new MailAccount(UUID.randomUUID(), MailProviderType.GMAIL, "a@gmail.com", "refresh-a");
    private static final MailAccount OUTLOOK =
            new MailAccount(
                    UUID.randomUUID(), MailProviderType.OUTLOOK, "b@outlook.com", "refresh-b");

    @Mock private MailAccountService accountService;
    @Mock private MailProviderRegistry providers;
    @Mock private AccountSyncStateService syncStateService;
    @Mock private ClassificationService classificationService;
    @Mock private MailProvider gmailProvider;
    @Mock private MailProvider outlookProvider;

    @InjectMocks private MailSyncService mailSyncService;

    @BeforeEach
    void coldStart() throws IOException {
        when(providers.forType(MailProviderType.GMAIL)).thenReturn(gmailProvider);
        when(providers.forType(MailProviderType.OUTLOOK)).thenReturn(outlookProvider);
        // No stored cursor -> the cold-start branch.
        when(syncStateService.load(any()))
                .thenAnswer(
                        call -> new AccountSyncStateEntity(call.getArgument(0), null, null, null));
        when(gmailProvider.currentCursor(any())).thenReturn("999");
        when(outlookProvider.currentCursor(any())).thenReturn("delta-999");
    }

    @Test
    void skipsKnownMessagesWithoutFetchingThem() throws IOException {
        when(gmailProvider.recentInboxIds(GMAIL)).thenReturn(List.of("a", "b", "c"));
        when(classificationService.alreadySeen(MailProviderType.GMAIL, "a")).thenReturn(true);
        when(classificationService.alreadySeen(MailProviderType.GMAIL, "b")).thenReturn(true);
        when(classificationService.alreadySeen(MailProviderType.GMAIL, "c")).thenReturn(false);
        when(gmailProvider.fetch(GMAIL, "c")).thenReturn(fetched(MailProviderType.GMAIL, "c"));
        when(classificationService.ingest(any())).thenReturn(Optional.of(new MessageEntity()));

        int ingested = mailSyncService.sync(GMAIL);

        assertThat(ingested).isEqualTo(1);
        // The whole point: a repeated cold start must not re-fetch what we already have.
        verify(gmailProvider, never()).fetch(eq(GMAIL), eq("a"));
        verify(gmailProvider, never()).fetch(eq(GMAIL), eq("b"));
        verify(gmailProvider).fetch(GMAIL, "c");
        verify(syncStateService).recordSuccess(GMAIL.id(), "999");
    }

    @Test
    void leavesTheCursorAloneWhenInterrupted() throws IOException {
        when(gmailProvider.recentInboxIds(GMAIL)).thenReturn(List.of("a", "b"));
        mailSyncService.stopAcceptingWork();

        int ingested = mailSyncService.sync(GMAIL);

        assertThat(ingested).isZero();
        // Recording a cursor here would skip every message still pending in this batch.
        verify(syncStateService, never()).recordSuccess(any(), anyString());
        verify(gmailProvider, never()).fetch(any(), anyString());
    }

    @Test
    void identityIsScopedToTheProvider() throws IOException {
        // Two providers may legitimately hand out the same id string. Asking "have we seen id x?"
        // without saying which provider would make one mailbox's message hide another's.
        when(outlookProvider.recentInboxIds(OUTLOOK)).thenReturn(List.of("shared-id"));
        when(classificationService.alreadySeen(MailProviderType.OUTLOOK, "shared-id"))
                .thenReturn(false);
        when(outlookProvider.fetch(OUTLOOK, "shared-id"))
                .thenReturn(fetched(MailProviderType.OUTLOOK, "shared-id"));
        when(classificationService.ingest(any())).thenReturn(Optional.of(new MessageEntity()));

        mailSyncService.sync(OUTLOOK);

        verify(classificationService).alreadySeen(MailProviderType.OUTLOOK, "shared-id");
        verify(classificationService, never()).alreadySeen(MailProviderType.GMAIL, "shared-id");
    }

    @Test
    void oneAccountFailingDoesNotStopTheOthers() throws IOException {
        when(accountService.connected()).thenReturn(List.of(OUTLOOK, GMAIL));
        when(outlookProvider.recentInboxIds(OUTLOOK)).thenThrow(new IOException("token revoked"));
        when(gmailProvider.recentInboxIds(GMAIL)).thenReturn(List.of());

        mailSyncService.poll();

        // Outlook's failure is recorded against Outlook, and Gmail still completed its sync.
        verify(syncStateService).recordError(eq(OUTLOOK.id()), anyString());
        verify(syncStateService).recordSuccess(GMAIL.id(), "999");
    }

    @Test
    void manualSyncReportsWhatItIngested() throws IOException {
        when(accountService.connected()).thenReturn(List.of(GMAIL));
        when(gmailProvider.recentInboxIds(GMAIL)).thenReturn(List.of("a"));
        when(classificationService.alreadySeen(MailProviderType.GMAIL, "a")).thenReturn(false);
        when(gmailProvider.fetch(GMAIL, "a")).thenReturn(fetched(MailProviderType.GMAIL, "a"));
        when(classificationService.ingest(any())).thenReturn(Optional.of(new MessageEntity()));

        SyncResultDto result = mailSyncService.syncNow();

        assertThat(result).isEqualTo(new SyncResultDto(false, 1, 1, 0));
    }

    @Test
    void manualSyncCountsFailuresSeparatelyFromFindingNothing() throws IOException {
        when(accountService.connected()).thenReturn(List.of(OUTLOOK));
        when(outlookProvider.recentInboxIds(OUTLOOK)).thenThrow(new IOException("token revoked"));

        SyncResultDto result = mailSyncService.syncNow();

        // Zero ingested with one failure is not "up to date" — the UI has to be able to tell them
        // apart, so the two counters are reported separately rather than collapsed into one.
        assertThat(result.ingested()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.alreadyRunning()).isFalse();
    }

    @Test
    void aScheduledTickIsSkippedWhileAManualSyncIsRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(accountService.connected()).thenReturn(List.of(GMAIL));
        when(gmailProvider.recentInboxIds(GMAIL))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return List.of();
                        });

        Thread manual = new Thread(mailSyncService::syncNow, "manual-sync");
        manual.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        mailSyncService.poll();

        release.countDown();
        manual.join(5_000);

        // The tick returned immediately instead of walking the same cursor concurrently — one
        // visit to the mailbox, made by the manual run.
        verify(accountService, times(1)).connected();
        verify(gmailProvider, times(1)).recentInboxIds(GMAIL);
    }

    @Test
    void aManualSyncDuringAScheduledTickSaysSoRatherThanReportingAnEmptyRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(accountService.connected()).thenReturn(List.of(GMAIL));
        when(gmailProvider.recentInboxIds(GMAIL))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return List.of();
                        });

        Thread scheduled = new Thread(mailSyncService::poll, "scheduled-poll");
        scheduled.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        SyncResultDto result = mailSyncService.syncNow();

        release.countDown();
        scheduled.join(5_000);

        // Reporting `false, 0, 0, 0` here would render as "checked, nothing new" when nothing was
        // checked at all.
        assertThat(result.alreadyRunning()).isTrue();
    }

    private static FetchedMessage fetched(MailProviderType provider, String id) {
        return new FetchedMessage(
                provider,
                id,
                "a@b.c",
                "subject",
                "snippet",
                java.time.Instant.EPOCH,
                java.util.Map.of());
    }
}
