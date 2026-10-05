package com.janne6565.hermes.client;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * POSTs automation payloads to user-configured URLs.
 *
 * <p>Short timeouts on purpose: this runs inside the poll loop, and a slow receiver must cost one
 * failed run, not the next message's latency. Never throws — the caller records the outcome.
 */
@Component
@Slf4j
public class WebhookClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public WebhookClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // Buffered so the body goes out with a Content-Length. The plain factory streams it
        // chunked, and plenty of webhook receivers (serverless endpoints, simple HTTP servers)
        // reject a chunked POST outright.
        this.restClient =
                builder.requestFactory(new BufferingClientHttpRequestFactory(factory)).build();
    }

    /**
     * @return empty on a 2xx; otherwise a short reason ({@code HTTP 502}, {@code timeout}) fit for
     *     the runs list. The URL is not logged — it may carry a token in its query string.
     */
    public Optional<String> post(String url, Object payload) {
        try {
            restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("User-Agent", "hermes-automations")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return Optional.empty();
        } catch (RestClientResponseException exception) {
            log.warn("Automation webhook answered {}", exception.getStatusCode().value());
            return Optional.of("HTTP " + exception.getStatusCode().value());
        } catch (Exception exception) {
            // The root cause, not the wrapper: Spring's message embeds the URL, the cause does not.
            Throwable cause = rootCause(exception);
            log.warn(
                    "Automation webhook failed: {}: {}",
                    cause.getClass().getSimpleName(),
                    cause.getMessage());
            return Optional.of(reason(exception));
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String reason(Exception exception) {
        String text = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) {
            return "timeout";
        }
        if (text.contains("connection refused")) {
            return "connection refused";
        }
        if (text.contains("unknownhost") || exception.getCause() instanceof UnknownHostException) {
            return "unknown host";
        }
        return "unreachable";
    }
}
