package com.janne6565.hermes.client;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to the classification sidecar over pod-local HTTP.
 *
 * <p>Every failure mode collapses to {@link Optional#empty()} on purpose: the caller's contract is
 * "classify if you can, otherwise I'll park this as fallback". Degradation is surfaced in the
 * digest and on the health screen, never silently swallowed.
 */
@Component
@Slf4j
public class SidecarClient {

    private final RestClient restClient;
    private final boolean enabled;

    /** Last error type the sidecar reported ({@code billing_error}, {@code rate_limit}, …). */
    private volatile String lastError;

    private volatile boolean healthy = true;

    public SidecarClient(RestClient.Builder builder, HermesProperties properties) {
        HermesProperties.Sidecar config = properties.getSidecar();
        this.enabled = config.isEnabled();
        this.restClient =
                builder.baseUrl(config.getBaseUrl())
                        .requestFactory(requestFactory(config.getTimeout()))
                        .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * @return the classifier's verdict, or empty when the sidecar is disabled, unreachable, out of
     *     credit, or returned something unparseable.
     */
    public Optional<ClassificationResponse> classify(ClassificationRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            ClassificationResponse response =
                    restClient
                            .post()
                            .uri("/classify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(ClassificationResponse.class);

            if (response == null || response.priority() == null) {
                markUnhealthy("empty_response");
                return Optional.empty();
            }
            markHealthy();
            return Optional.of(response);
        } catch (Exception exception) {
            // The sidecar returns 503 with an error type for billing/rate-limit/auth failures;
            // anything else (timeout, connection refused) lands here too and is treated the same.
            markUnhealthy(errorTypeOf(exception));
            log.warn("Sidecar classification failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private static String errorTypeOf(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "unknown";
        }
        for (String known :
                new String[] {
                    "billing_error", "rate_limit", "overloaded", "authentication_failed"
                }) {
            if (message.contains(known)) {
                return known;
            }
        }
        return "unavailable";
    }

    private void markHealthy() {
        healthy = true;
        lastError = null;
    }

    private void markUnhealthy(String error) {
        healthy = false;
        lastError = error;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHealthy() {
        return !enabled || healthy;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /**
     * Asks the sidecar what it knows about its own credentials.
     *
     * <p>Deliberately a live call rather than cached state: the health screen is the one place that
     * has to be current, and it is a loopback request to a process in the same pod. A failure is
     * reported as empty rather than as a fabricated "unknown" row.
     */
    public Optional<SidecarHealth> health() {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    restClient.get().uri("/health").retrieve().body(SidecarHealth.class));
        } catch (Exception exception) {
            // A sidecar that is down still answers the *question* — it has no working credential
            // to report — but "we could not ask" and "the answer is no" are different states, so
            // this stays empty and the caller decides how to render it.
            log.debug("Sidecar health probe failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /** The sidecar's own view of itself, including what it can see of its credentials. */
    public record SidecarHealth(
            String status, String model, String lastError, Credentials credentials) {

        public record Credentials(boolean oauthToken, boolean apiKeyUnset, Instant expiresAt) {}
    }

    /** Exactly the fields the plan permits leaving the cluster: sender, subject, short snippet. */
    public record ClassificationRequest(String sender, String subject, String snippet) {}

    public record ClassificationResponse(Priority priority, String reason, String summary) {}
}
