package com.janne6565.hermes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The scrape Grafana Alloy makes: unauthenticated, straight to the pod.
 *
 * <p>Guards the two ways it silently goes wrong: the endpoint 404s (no Prometheus registry on the
 * classpath, which is how it shipped), or it starts publishing a bucket histogram again and spends
 * the cluster's series budget on data Alloy throws away.
 *
 * <p>The test {@code application.yaml} shadows the main one on the classpath, so the main file is
 * loaded explicitly as the base and the test file layered over it. Without that this would assert
 * against Boot's defaults, not the management block that actually ships.
 *
 * <p>{@code @AutoConfigureMockMvc} rather than a bare {@code webAppContextSetup}: only that applies
 * the servlet filters, and the request timer is recorded by one of them.
 */
@SpringBootTest(
        properties =
                "spring.config.location=file:src/main/resources/application.yaml,"
                        + "classpath:application.yaml")
@AutoConfigureMockMvc
class PrometheusEndpointTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void servesLeanMetricsWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());

        String body =
                mockMvc.perform(get("/actuator/prometheus"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .contains("http_server_requests_seconds")
                .contains("quantile=\"0.95\"")
                .contains("application=\"hermes-backend\"")
                .contains("hermes_messages_triaged_total")
                .doesNotContain("_bucket")
                .doesNotContain("jvm_classes_")
                .doesNotContain("http_server_requests_active");
    }
}
