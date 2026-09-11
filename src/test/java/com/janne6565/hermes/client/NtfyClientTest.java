package com.janne6565.hermes.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.services.metrics.HermesMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class NtfyClientTest {

    /**
     * ntfy keeps a published body as the message text only while it is valid UTF-8 — anything else
     * it stores as a file, and the phone then shows a .txt attachment instead of the digest. A
     * German body written in Spring's default ISO-8859-1 for {@code text/plain} is exactly that
     * case, so this asserts the bytes that actually go over the wire.
     */
    @Test
    void publishesTheBodyAsUtf8() throws IOException {
        String body = "Der CI-Test für music-collector ist fehlgeschlagen — vier Newsletter.";
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    received.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            HermesProperties properties = new HermesProperties();
            properties.getNtfy().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getNtfy().setTopic("hermes-mail");

            boolean published =
                    new NtfyClient(
                                    RestClient.builder(),
                                    properties,
                                    new HermesMetrics(new SimpleMeterRegistry()))
                            .publish(NtfyClient.Notification.normal("Digest", body, "newspaper"));

            assertThat(published).isTrue();
            assertThat(path.get()).isEqualTo("/hermes-mail");
            assertThat(contentType.get()).containsIgnoringCase("charset=UTF-8");
            assertThat(received.get()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }
}
