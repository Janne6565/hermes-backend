package com.janne6565.hermes.client;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.Priority;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * Asks the sidecar to narrate a day.
     *
     * <p>Deliberately does not move {@link #isHealthy()} or {@link #lastError()} in either
     * direction. Those two mean "mail is being classified" — the one thing the health screen exists
     * to answer — and a missing paragraph in the evening digest is not that. A failure here is
     * simply an empty narrative, and the digest goes out as the plain list it was before.
     *
     * @return the day's prose summary, or empty when the sidecar is disabled, unreachable, or
     *     produced nothing usable. Never a fabricated sentence.
     */
    public Optional<String> summarise(DigestSummaryRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            SummaryResponse response =
                    restClient
                            .post()
                            .uri("/summary")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(SummaryResponse.class);

            return Optional.ofNullable(response)
                    .map(SummaryResponse::narrative)
                    .filter(narrative -> !narrative.isBlank());
        } catch (Exception exception) {
            log.warn("Sidecar digest summary failed: {}", exception.getMessage());
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

    /**
     * Exactly the fields the plan permits leaving the cluster: sender, subject, short snippet.
     *
     * @param categories the vocabulary the classifier must pick a category from. Sent rather than
     *     hard-coded in the prompt because the user can add categories at runtime, and a model
     *     answering with a bucket that no longer exists is a silent miscategorisation.
     */
    public record ClassificationRequest(
            String sender, String subject, String snippet, List<String> categories) {}

    /**
     * @param category one of the names from the request, or null if the classifier declined.
     * @param categoryConfidence 0..1; below the configured threshold the message is queued for the
     *     user rather than accepted quietly.
     * @param categoryAlternative the runner-up, offered as the second chip in that queue.
     */
    public record ClassificationResponse(
            Priority priority,
            String reason,
            String summary,
            String category,
            Float categoryConfidence,
            String categoryAlternative) {}

    /**
     * One day — or one span — as the narrator sees it.
     *
     * <p>Carries the same three fields per message the classifier was allowed — sender, subject and
     * the one-line summary it already produced — and never the body. The narrator is describing
     * work that has already been done, so it needs no more raw mail than the classifier did.
     *
     * @param date the day, on the evening send. Null for a range.
     * @param from first day of a span, set only by the ad-hoc range report. The sidecar picks its
     *     prompt from which of {@code date} and {@code from}/{@code to} arrived, so exactly one of
     *     the two forms must be filled in.
     * @param to last day of that span, inclusive.
     * @param alerts titles of the unresolved alerts in the period, so the summary can admit that
     *     something is still on fire rather than closing on a tidy note.
     */
    public record DigestSummaryRequest(
            LocalDate date,
            LocalDate from,
            LocalDate to,
            DigestDto.Counts counts,
            List<Item> high,
            List<Item> normal,
            List<DigestDto.NoiseSummary.Category> noiseCategories,
            List<String> alerts,
            int unclassified) {

        /** {@code date} is null on the daily path, where every line would carry the same one. */
        public record Item(String sender, String subject, String summary, LocalDate date) {}
    }

    public record SummaryResponse(String narrative) {}
}
