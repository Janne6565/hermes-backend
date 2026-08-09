package com.janne6565.hermes.services.alerts;

import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.model.action.AlertWebhookRequest;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.AlertOverviewDto;
import com.janne6565.hermes.model.core.AlertSeverity;
import com.janne6565.hermes.model.core.AlertSource;
import com.janne6565.hermes.model.exception.AlertNotFoundException;
import com.janne6565.hermes.repository.AlertEventRepository;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook intake for Grafana and SigNoz.
 *
 * <p>This is the authoritative alert path — faster than mail, structured, and independent of Gmail
 * being reachable. The mail-based sender rules stay in place only as a safety net.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final AlertEventRepository alertEventRepository;
    private final AlertLinkResolver alertLinkResolver;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Records an alert and pushes it if the severity warrants an interrupt.
     *
     * <p>Re-fires of an already-open alert update the existing row instead of creating a new one,
     * so a flapping probe shows up once with a fresh timestamp rather than fifty times.
     */
    @Transactional
    public AlertEventEntity intake(AlertWebhookRequest request, String rawPayload) {
        AlertSeverity severity = AlertSeverity.fromWire(request.severity());
        String fingerprint = request.fingerprint();

        Optional<AlertEventEntity> existing =
                fingerprint == null
                        ? Optional.empty()
                        : alertEventRepository.findByFingerprintAndResolvedAtIsNull(fingerprint);

        if (severity == AlertSeverity.RESOLVED) {
            existing.ifPresent(alert -> alert.setResolvedAt(Instant.now(clock)));
            return existing.orElseGet(() -> persist(request, severity, rawPayload, true));
        }

        if (existing.isPresent()) {
            AlertEventEntity alert = existing.get();
            alert.setSeverity(severity);
            alert.setTitle(request.title());
            alert.setPayload(rawPayload);
            // Already pushed once — a still-firing alert does not re-interrupt.
            return alert;
        }

        AlertEventEntity alert = persist(request, severity, rawPayload, false);
        if (!severity.warrantsPush()) {
            log.debug("Alert '{}' ({}) held for the digest", alert.getTitle(), severity.wire());
            return alert;
        }
        if (isSnoozed(fingerprint)) {
            // Snoozed alerts are still recorded and still shown — snooze silences the phone, it
            // does not hide the problem.
            log.info("Alert '{}' is snoozed; recorded without pushing", alert.getTitle());
            return alert;
        }
        alert.setNotified(notificationService.pushAlert(alert));
        return alert;
    }

    private AlertEventEntity persist(
            AlertWebhookRequest request, AlertSeverity severity, String payload, boolean resolved) {
        AlertEventEntity alert =
                AlertEventEntity.builder()
                        .source(AlertSource.fromWire(request.source()))
                        .severity(severity)
                        .title(request.title())
                        .appName(request.app())
                        .fingerprint(request.fingerprint())
                        .payload(payload)
                        .receivedAt(Instant.now(clock))
                        .resolvedAt(resolved ? Instant.now(clock) : null)
                        .build();
        return alertEventRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<AlertEventEntity> unresolved() {
        return alertEventRepository.findByResolvedAtIsNullOrderByReceivedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<AlertEventEntity> between(Instant from, Instant to) {
        return alertEventRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to);
    }

    /** Everything the alerts screen shows, over a rolling window ending now. */
    @Transactional(readOnly = true)
    public AlertOverviewDto overview(int days) {
        Instant now = Instant.now(clock);
        Instant from = now.minus(Duration.ofDays(days));
        List<AlertEventEntity> window =
                alertEventRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, now);

        List<AlertEventEntity> unresolved =
                window.stream().filter(alert -> alert.getResolvedAt() == null).toList();
        List<AlertEventEntity> resolved =
                window.stream().filter(alert -> alert.getResolvedAt() != null).toList();

        // Counted over the whole window, including resolved ones: "you were pushed twice today"
        // is a fact about the day, not about what is still open.
        int pushed = (int) window.stream().filter(AlertEventEntity::isNotified).count();

        return new AlertOverviewDto(
                unresolved.stream().map(this::toDto).toList(),
                resolved.stream().map(this::toDto).toList(),
                new AlertOverviewDto.Routing(pushed, window.size() - pushed, resolved.size()),
                sourceStates(window));
    }

    private List<AlertOverviewDto.SourceState> sourceStates(List<AlertEventEntity> window) {
        return Arrays.stream(AlertSource.values())
                .map(
                        source -> {
                            List<AlertEventEntity> forSource =
                                    window.stream()
                                            .filter(alert -> alert.getSource() == source)
                                            .toList();
                            // "Ever received" distinguishes a quiet source from one that was never
                            // wired up — a webhook nobody configured looks identical to a healthy
                            // one on a calm day, and those need different reactions.
                            Instant lastEver =
                                    alertEventRepository
                                            .findFirstBySourceOrderByReceivedAtDesc(source)
                                            .map(AlertEventEntity::getReceivedAt)
                                            .orElse(null);
                            return new AlertOverviewDto.SourceState(
                                    source, forSource.size(), lastEver, lastEver != null);
                        })
                .toList();
    }

    /**
     * Marks an alert as seen.
     *
     * <p>Does not touch {@code resolvedAt} — only the source may say the underlying problem is
     * over, and an operator clicking "acknowledge" has not fixed anything yet.
     */
    @Transactional
    public AlertEventDto acknowledge(UUID id) {
        AlertEventEntity alert = require(id);
        alert.setAcknowledgedAt(Instant.now(clock));
        return toDto(alert);
    }

    /** Suppresses pushes for this alert's fingerprint until the snooze expires. */
    @Transactional
    public AlertEventDto snooze(UUID id, Duration duration) {
        AlertEventEntity alert = require(id);
        alert.setSnoozedUntil(Instant.now(clock).plus(duration));
        return toDto(alert);
    }

    private AlertEventEntity require(UUID id) {
        return alertEventRepository.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
    }

    public AlertEventDto toDto(AlertEventEntity entity) {
        return AlertEventDto.from(entity, alertLinkResolver.urlFor(entity));
    }

    /**
     * @return true when a live snooze covers this fingerprint.
     */
    private boolean isSnoozed(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return alertEventRepository
                .findFirstByFingerprintAndSnoozedUntilAfterOrderBySnoozedUntilDesc(
                        fingerprint, Instant.now(clock))
                .isPresent();
    }

    /**
     * Serialises the request for the {@code payload} column. A serialisation failure must not lose
     * the alert — the debugging copy is nice to have, the alert itself is not optional.
     */
    public String asJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            log.warn("Could not serialise alert payload: {}", exception.getMessage());
            return "{}";
        }
    }
}
