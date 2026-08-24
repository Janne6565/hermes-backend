package com.janne6565.hermes.client;

import com.janne6565.hermes.configuration.HermesProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Publishes to the self-hosted ntfy instance. This is the only interrupt channel the user has. */
@Component
@Slf4j
public class NtfyClient {

    private final RestClient restClient;
    private final HermesProperties.Ntfy config;

    private volatile boolean healthy = true;

    /**
     * The charset has to be spelled out: Spring's String converter falls back to ISO-8859-1 for a
     * {@code text/plain} without one, and ntfy only treats a body as the message text when it is
     * valid UTF-8 — a single umlaut in latin-1 makes it publish the digest as a .txt attachment
     * instead.
     */
    private static final MediaType UTF8_TEXT =
            new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    public NtfyClient(RestClient.Builder builder, HermesProperties properties) {
        this.config = properties.getNtfy();
        this.restClient =
                builder.baseUrl(config.getBaseUrl())
                        .requestFactory(requestFactory(config.getTimeout()))
                        .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * @return true when ntfy accepted the message. A false here means the user was not interrupted
     *     — callers must not record {@code notified_at}.
     */
    public boolean publish(Notification notification) {
        try {
            restClient
                    .post()
                    .uri("/{topic}", config.getTopic())
                    .headers(
                            headers -> {
                                if (!config.getToken().isBlank()) {
                                    headers.setBearerAuth(config.getToken());
                                }
                                headers.set("Title", asciiHeader(notification.title()));
                                headers.set("Priority", notification.priority().wire());
                                if (notification.tags() != null && !notification.tags().isBlank()) {
                                    headers.set("Tags", notification.tags());
                                }
                                if (notification.clickUrl() != null) {
                                    headers.set("Click", notification.clickUrl());
                                }
                                headers.setContentType(UTF8_TEXT);
                            })
                    .body(notification.body())
                    .retrieve()
                    .toBodilessEntity();
            healthy = true;
            return true;
        } catch (Exception exception) {
            healthy = false;
            log.error(
                    "ntfy publish failed for '{}': {}",
                    notification.title(),
                    exception.getMessage());
            return false;
        }
    }

    /**
     * ntfy carries the title in an HTTP header, and header values are latin-1 on the wire — an
     * umlaut in a German subject would otherwise be mangled or rejected. Non-latin-1 characters are
     * dropped rather than escaped, since the full subject is in the body anyway.
     */
    private static String asciiHeader(String value) {
        return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1)
                .replaceAll("[\\r\\n]", " ");
    }

    public boolean isHealthy() {
        return healthy;
    }

    public String topic() {
        return config.getTopic();
    }

    public record Notification(
            String title, String body, NtfyPriority priority, String tags, String clickUrl) {

        public static Notification urgent(String title, String body, String tags, String clickUrl) {
            return new Notification(title, body, NtfyPriority.URGENT, tags, clickUrl);
        }

        public static Notification normal(String title, String body, String tags) {
            return new Notification(title, body, NtfyPriority.DEFAULT, tags, null);
        }
    }

    /** ntfy's own five-level scale; {@code urgent} is what overrides Do Not Disturb. */
    public enum NtfyPriority {
        MIN,
        LOW,
        DEFAULT,
        HIGH,
        URGENT;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
