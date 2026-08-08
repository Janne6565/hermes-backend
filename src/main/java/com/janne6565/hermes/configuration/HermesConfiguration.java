package com.janne6565.hermes.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HermesConfiguration {

    /** Injected rather than static so the digest and quiet-hours logic is testable. */
    @Bean
    public Clock clock(HermesProperties properties) {
        return Clock.system(properties.getTimezone());
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
