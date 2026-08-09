package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.controller.v1.schema.AlertApi;
import com.janne6565.hermes.model.action.AlertWebhookRequest;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.AlertOverviewDto;
import com.janne6565.hermes.model.exception.InvalidWebhookSecretException;
import com.janne6565.hermes.services.alerts.AlertService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint reachable from outside the cluster.
 *
 * <p>Authentication is a shared secret rather than a signature because both Grafana contact points
 * and SigNoz alert channels can only send a static header. The comparison is constant-time so the
 * endpoint can't be used as an oracle to recover the secret byte by byte.
 */
@RestController
@RequiredArgsConstructor
public class AlertController implements AlertApi {

    private final AlertService alertService;
    private final HermesProperties properties;

    @Override
    public ResponseEntity<AlertEventDto> receive(String token, AlertWebhookRequest request) {
        requireValidSecret(token);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        AlertEventDto.from(
                                alertService.intake(request, alertService.asJson(request))));
    }

    @Override
    public ResponseEntity<List<AlertEventDto>> unresolved() {
        return ResponseEntity.ok(
                alertService.unresolved().stream().map(alertService::toDto).toList());
    }

    @Override
    public ResponseEntity<AlertOverviewDto> overview(int days) {
        return ResponseEntity.ok(alertService.overview(days));
    }

    @Override
    public ResponseEntity<AlertEventDto> acknowledge(UUID id) {
        return ResponseEntity.ok(alertService.acknowledge(id));
    }

    @Override
    public ResponseEntity<AlertEventDto> snooze(UUID id, Integer hours) {
        Duration duration =
                hours == null || hours <= 0
                        ? properties.getAlerts().getDefaultSnooze()
                        : Duration.ofHours(hours);
        return ResponseEntity.ok(alertService.snooze(id, duration));
    }

    private void requireValidSecret(String presented) {
        String expected = properties.getAlerts().getWebhookSecret();
        // An unset secret must fail closed: an empty expected value would otherwise make the
        // endpoint open to anyone who omits the header.
        if (expected == null || expected.isBlank() || presented == null) {
            throw new InvalidWebhookSecretException();
        }
        boolean matches =
                MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new InvalidWebhookSecretException();
        }
    }
}
