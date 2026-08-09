package com.janne6565.hermes.model.core;

import java.time.Instant;
import java.util.Map;

/**
 * A message as the triage pipeline sees it — flat, and belonging to no provider.
 *
 * <p>This is the whole reason the rule engine and the classifier never needed to know what Gmail
 * is: they only ever read these fields. It carries exactly what triage needs and nothing more, so
 * there is no path by which a full body or an attachment can reach the classifier.
 *
 * @param externalId the provider's own id — unique only within that provider
 * @param headers all headers, lowercased keys. The rule engine needs these for {@code
 *     List-Unsubscribe} and friends.
 */
public record FetchedMessage(
        MailProviderType provider,
        String externalId,
        String sender,
        String subject,
        String snippet,
        Instant receivedAt,
        Map<String, String> headers) {}
