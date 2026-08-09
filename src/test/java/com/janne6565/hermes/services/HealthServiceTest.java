package com.janne6565.hermes.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.NtfyClient;
import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AccountSyncStateEntity;
import com.janne6565.hermes.entity.MailAccountEntity;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.HealthDto;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.repository.RuleRepository;
import com.janne6565.hermes.services.auth.MailAccountService;
import com.janne6565.hermes.services.mail.AccountSyncStateService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The health headline is the one place a reader looks to decide whether to trust the triage, so the
 * property that matters is that it never contradicts the panels underneath it. The screen renders a
 * "DEGRADED — n unclassified" notice straight off {@code fallbackCount}; the status field has to
 * agree, or the page tells the reader two different things at once.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthServiceTest {

    @Mock private MailAccountService accountService;
    @Mock private AccountSyncStateService syncStateService;
    @Mock private SidecarClient sidecarClient;
    @Mock private NtfyClient ntfyClient;
    @Mock private MessageRepository messageRepository;
    @Mock private RuleRepository ruleRepository;

    // A real properties object rather than a mock: every nested block has a usable default, and
    // the status rule under test does not read any of them.
    private final HermesProperties properties = new HermesProperties();

    private HealthService service;
    private final Instant now = Instant.parse("2026-08-10T00:00:00Z");

    @BeforeEach
    void setUp() {
        service =
                new HealthService(
                        accountService,
                        syncStateService,
                        sidecarClient,
                        ntfyClient,
                        messageRepository,
                        ruleRepository,
                        properties,
                        Clock.fixed(now, ZoneOffset.UTC));

        MailAccountEntity account = new MailAccountEntity();
        account.setId(UUID.randomUUID());
        account.setProvider(MailProviderType.GMAIL);
        account.setEmail("reader@example.com");
        when(accountService.all()).thenReturn(List.of(account));

        AccountSyncStateEntity state = new AccountSyncStateEntity();
        // Fresh enough that the sync itself is never the reason for a non-ok status here.
        state.setLastSync(now);
        when(syncStateService.find(account.getId())).thenReturn(Optional.of(state));

        when(sidecarClient.isHealthy()).thenReturn(true);
        when(sidecarClient.lastError()).thenReturn(Optional.empty());
        when(ntfyClient.isHealthy()).thenReturn(true);
        when(ntfyClient.topic()).thenReturn("hermes-mail");
    }

    @Test
    void reportsOkWhenNothingIsParkedInFallback() {
        when(messageRepository.countByClassifiedBy(ClassifiedBy.FALLBACK)).thenReturn(0L);

        HealthDto health = service.snapshot();

        assertThat(health.status()).isEqualTo("ok");
        assertThat(health.fallbackCount()).isZero();
    }

    @Test
    void reportsDegradedWhileMessagesSitInFallback() {
        when(messageRepository.countByClassifiedBy(ClassifiedBy.FALLBACK)).thenReturn(2L);

        HealthDto health = service.snapshot();

        // The old code returned "ok" here, which the screen rendered as "All good" directly above
        // its own "DEGRADED — 2 unclassified" notice.
        assertThat(health.status()).isEqualTo("degraded");
        assertThat(health.fallbackCount()).isEqualTo(2L);
    }

    @Test
    void aStalledSyncStillOutranksFallback() {
        when(messageRepository.countByClassifiedBy(ClassifiedBy.FALLBACK)).thenReturn(2L);
        when(syncStateService.find(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(stalled()));

        assertThat(service.snapshot().status()).isEqualTo("broken");
    }

    private AccountSyncStateEntity stalled() {
        AccountSyncStateEntity state = new AccountSyncStateEntity();
        state.setLastSync(now.minusSeconds(3600));
        return state;
    }
}
