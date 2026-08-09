package com.janne6565.hermes.services;

import com.janne6565.hermes.client.NtfyClient;
import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.SyncStateEntity;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.HealthDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.repository.RuleRepository;
import com.janne6565.hermes.services.auth.GmailClientProvider;
import com.janne6565.hermes.services.gmail.SyncStateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the health screen and the widget's traffic light.
 *
 * <p>The three-state rule is the one the UI depends on: green means everything ran, amber means the
 * classifier fell back but nothing was lost, red means mail is not being read at all.
 */
@Service
@RequiredArgsConstructor
public class HealthService {

    /**
     * A sync gap longer than this means the poll loop is genuinely stuck, not just between ticks.
     */
    private static final Duration SYNC_STALE_AFTER = Duration.ofMinutes(15);

    private final SyncStateService syncStateService;
    private final GmailClientProvider gmailClientProvider;
    private final SidecarClient sidecarClient;
    private final NtfyClient ntfyClient;
    private final MessageRepository messageRepository;
    private final RuleRepository ruleRepository;
    private final HermesProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HealthDto snapshot() {
        SyncStateEntity sync = syncStateService.current();
        boolean connected = gmailClientProvider.isConnected();
        // Not connected is a setup state, not a fault: the onboarding screen handles it, and the
        // health dot must not scream red at someone who simply hasn't signed in yet.
        boolean syncOk =
                !connected || (sync.getLastError() == null && !isStale(sync.getLastSync()));
        boolean sidecarOk = sidecarClient.isHealthy();

        long fallback = messageRepository.countByClassifiedBy(ClassifiedBy.FALLBACK);

        List<HealthDto.ServiceState> services =
                List.of(
                        new HealthDto.ServiceState(
                                "gmail sync",
                                !connected ? "warn" : (syncOk ? "ok" : "bad"),
                                !connected ? "NOT CONNECTED" : (syncOk ? "OK" : "STALLED"),
                                !connected
                                        ? "sign in with Google to start"
                                        : (sync.getLastSync() == null
                                                ? "never synced"
                                                : "last " + sync.getLastSync().toString())),
                        new HealthDto.ServiceState(
                                "claude sidecar",
                                sidecarOk ? "ok" : "bad",
                                sidecarOk ? "OK" : "503",
                                sidecarClient.lastError().orElse("reachable")),
                        new HealthDto.ServiceState("postgres", "ok", "OK", "reachable"),
                        new HealthDto.ServiceState(
                                "ntfy",
                                ntfyClient.isHealthy() ? "ok" : "bad",
                                ntfyClient.isHealthy() ? "OK" : "FAIL",
                                "topic " + ntfyClient.topic()),
                        new HealthDto.ServiceState(
                                "webhook intake", "ok", "OK", "grafana + signoz"));

        // Mail not being read is the only red. A degraded classifier still stores everything,
        // and a not-yet-connected account is amber (setup pending), never red.
        String status = !syncOk ? "broken" : (connected && sidecarOk ? "ok" : "degraded");

        return new HealthDto(
                status,
                services,
                sync.getLastSync(),
                sync.getHistoryId(),
                sync.getLastError(),
                fallback,
                new HealthDto.ClassificationMix(
                        messageRepository.countByClassifiedBy(ClassifiedBy.RULE),
                        messageRepository.countByClassifiedBy(ClassifiedBy.LLM),
                        fallback),
                credentials(connected),
                new HealthDto.Shadow(
                        properties.isShadowMode(),
                        messageRepository.count(),
                        messageRepository.countByPriority(Priority.HIGH),
                        ruleRepository.countBySource(RuleSource.FEEDBACK)));
    }

    /**
     * The credential rows.
     *
     * <p>Only ever presence and validity — no value, no prefix, no length. The Claude rows come
     * from the sidecar because that is the only process that can see its own environment.
     */
    private List<HealthDto.CredentialState> credentials(boolean gmailConnected) {
        List<HealthDto.CredentialState> rows = new ArrayList<>();

        rows.add(
                new HealthDto.CredentialState(
                        "gmail refresh token",
                        gmailConnected ? "ok" : "warn",
                        gmailConnected ? "valid" : "not connected"));

        Optional<SidecarClient.SidecarHealth> sidecar = sidecarClient.health();
        if (sidecar.isEmpty()) {
            rows.add(
                    new HealthDto.CredentialState(
                            "claude oauth token", "unknown", "sidecar did not answer"));
            rows.add(new HealthDto.CredentialState("ANTHROPIC_API_KEY", "unknown", "unreadable"));
        } else {
            SidecarClient.SidecarHealth.Credentials credentials = sidecar.get().credentials();
            rows.add(claudeToken(credentials));
            // Unset is the *good* state here: either shadowing variable would silently move
            // billing off the subscription credit and onto pay-per-token API credits.
            boolean unset = credentials != null && credentials.apiKeyUnset();
            rows.add(
                    new HealthDto.CredentialState(
                            "ANTHROPIC_API_KEY", unset ? "ok" : "bad", unset ? "unset" : "SET"));
        }

        boolean webhookConfigured = !properties.getAlerts().getWebhookSecret().isBlank();
        rows.add(
                new HealthDto.CredentialState(
                        "alert webhook secret",
                        webhookConfigured ? "ok" : "warn",
                        webhookConfigured ? "valid" : "unset — intake rejects everything"));

        return List.copyOf(rows);
    }

    private HealthDto.CredentialState claudeToken(
            SidecarClient.SidecarHealth.Credentials credentials) {
        if (credentials == null || !credentials.oauthToken()) {
            return new HealthDto.CredentialState("claude oauth token", "bad", "missing");
        }
        if (credentials.expiresAt() == null) {
            // The token is opaque, so with no expiry hint configured there is simply nothing to
            // report. Saying "valid" would be inventing a fact the service does not have.
            return new HealthDto.CredentialState("claude oauth token", "ok", "present");
        }
        long days = Duration.between(Instant.now(clock), credentials.expiresAt()).toDays();
        if (days < 0) {
            return new HealthDto.CredentialState("claude oauth token", "bad", "expired");
        }
        return new HealthDto.CredentialState(
                "claude oauth token", days <= 14 ? "warn" : "ok", days + " d left");
    }

    private boolean isStale(Instant lastSync) {
        return lastSync == null || lastSync.isBefore(Instant.now(clock).minus(SYNC_STALE_AFTER));
    }
}
