package com.janne6565.hermes.services.alerts;

import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.model.action.AlertWebhookRequest;
import com.janne6565.hermes.model.core.AlertSeverity;
import com.janne6565.hermes.model.core.AlertSource;
import com.janne6565.hermes.repository.AlertEventRepository;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        if (severity.warrantsPush()) {
            alert.setNotified(notificationService.pushAlert(alert));
        } else {
            log.debug("Alert '{}' ({}) held for the digest", alert.getTitle(), severity.wire());
        }
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
