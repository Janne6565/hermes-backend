package com.janne6565.hermes.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Posts the exact JSON the browser sends.
 *
 * <p>This file exists because of a bug the unit tests could not have caught: they build request
 * records with {@code new}, which skips Jackson entirely, so a body that never deserialises still
 * passes every assertion about what the service does with it. Jackson 3 rejects an absent primitive
 * component of a record, and every "needs a call" chip omits the optional {@code applyToDomain} —
 * so the endpoint returned 400 for its only real caller while its tests were green.
 *
 * <p>The bodies below are copied from the frontend's request shapes, not from the Java records. A
 * 404 or 409 here is a pass: it means the payload bound and the handler ran. Only a 400 is the
 * failure this guards against.
 */
@SpringBootTest
class RequestBindingTest {

    private static final String ABSENT_UUID = "00000000-0000-0000-0000-000000000000";

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void assignBindsTheBodyTheCategoryChipsSend() throws Exception {
        mockMvc()
                .perform(
                        post("/api/v1/categories/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"messageId":"%s","categoryId":"%s"}"""
                                                .formatted(ABSENT_UUID, ABSENT_UUID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignStillAcceptsTheOptionalFlagsWhenPresent() throws Exception {
        mockMvc()
                .perform(
                        post("/api/v1/categories/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"messageId":"%s","categoryId":"%s",\
                                        "applyToDomain":true,"learn":false}"""
                                                .formatted(ABSENT_UUID, ABSENT_UUID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void feedbackBindsWithoutItsOptionalFlagToo() throws Exception {
        // Today the frontend always sends applyToDomain here, so this one worked by luck rather
        // than by contract. Pinning it means a caller that stops sending it is not a 400.
        mockMvc()
                .perform(
                        post("/api/v1/rules/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"messageId":"%s","shouldHaveBeen":"noise"}"""
                                                .formatted(ABSENT_UUID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void dismissBindsWithAnEmptyBody() throws Exception {
        mockMvc()
                .perform(
                        post("/api/v1/messages/%s/dismiss".formatted(ABSENT_UUID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBodyMissingARequiredFieldIsStillRejected() throws Exception {
        // The fix must not turn into blanket leniency: categoryId is genuinely required.
        mockMvc()
                .perform(
                        post("/api/v1/categories/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"messageId":"%s"}""".formatted(ABSENT_UUID)))
                .andExpect(status().isBadRequest());
    }
}
