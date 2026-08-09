package com.janne6565.hermes.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The API hands out {@code "high"} in every response, so it has to accept {@code "high"} back as a
 * filter. It did not: {@code @JsonValue}/{@code @JsonCreator} govern request bodies, while a
 * {@code @RequestParam} enum is bound by Spring's own case-sensitive {@code Enum.valueOf}. The
 * search screen filtered by priority and got a 400 for its trouble.
 */
@SpringBootTest
class WireEnumBindingTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void acceptsTheLowercasePriorityItPublishes() throws Exception {
        mockMvc()
                .perform(get("/api/v1/messages").param("priority", "high").param("limit", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsTheLowercaseClassifiedByItPublishes() throws Exception {
        mockMvc()
                .perform(get("/api/v1/messages").param("classifiedBy", "fallback"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsALowercaseRuleTypeOnTheDryRun() throws Exception {
        mockMvc()
                .perform(
                        get("/api/v1/rules/dry-run")
                                .param("type", "sender")
                                .param("pattern", "billing@hetzner.com"))
                .andExpect(status().isOk());
    }

    @Test
    void stillAcceptsTheUppercaseFormTypedByHand() throws Exception {
        mockMvc()
                .perform(get("/api/v1/messages").param("priority", "HIGH"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsACategoryFilter() throws Exception {
        mockMvc()
                .perform(get("/api/v1/messages").param("category", "billing"))
                .andExpect(status().isOk());
    }

    @Test
    void aGenuineTypoIsStillARejection() throws Exception {
        // Leniency about case must not become leniency about meaning — an unknown tier has to fail
        // loudly rather than silently widening the query to everything.
        mockMvc()
                .perform(get("/api/v1/messages").param("priority", "urgent"))
                .andExpect(status().isBadRequest());
    }
}
