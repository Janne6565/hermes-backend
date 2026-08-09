package com.janne6565.hermes.client;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.CursorPage;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.services.auth.GmailClientProvider;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gmail, behind {@link MailProvider}.
 *
 * <p>Thin and read-only.
 *
 * <p>Everything above this class works with {@link FetchedMessage} — a flat record of exactly the
 * fields triage needs. Nothing else (attachments, full bodies, thread structure) is ever read out
 * of the API response, so there is no path by which more than the snippet can leak downstream.
 */
@Component
@Slf4j
public class GmailProvider implements MailProvider {

    private static final String HEADER_FROM = "From";
    private static final String HEADER_SUBJECT = "Subject";

    private final GmailClientProvider clientProvider;
    private final HermesProperties.Gmail config;

    public GmailProvider(GmailClientProvider clientProvider, HermesProperties properties) {
        this.clientProvider = clientProvider;
        this.config = properties.getGmail();
    }

    private Gmail gmail(MailAccount account) throws IOException {
        return clientProvider.forAccount(account);
    }

    @Override
    public MailProviderType type() {
        return MailProviderType.GMAIL;
    }

    /** The mailbox's current history cursor — used to seed sync on a cold start. */
    @Override
    public String currentCursor(MailAccount account) throws IOException {
        return String.valueOf(
                gmail(account).users().getProfile(config.getUserId()).execute().getHistoryId());
    }

    /**
     * Incremental sync from a stored cursor.
     *
     * @return the ids added since {@code startHistoryId}, plus the new cursor — or {@link
     *     Optional#empty()} when Gmail has expired the cursor and a cold start is required.
     */
    @Override
    public Optional<CursorPage> messagesSince(MailAccount account, String startHistoryId)
            throws IOException {
        List<String> added = new ArrayList<>();
        String pageToken = null;
        String newHistoryId = startHistoryId;

        try {
            do {
                ListHistoryResponse response =
                        gmail(account)
                                .users()
                                .history()
                                .list(config.getUserId())
                                .setStartHistoryId(new BigInteger(startHistoryId))
                                .setHistoryTypes(List.of("messageAdded"))
                                .setPageToken(pageToken)
                                .execute();

                if (response.getHistory() != null) {
                    for (History history : response.getHistory()) {
                        if (history.getMessagesAdded() == null) {
                            continue;
                        }
                        history.getMessagesAdded().stream()
                                .map(entry -> entry.getMessage().getId())
                                .forEach(added::add);
                    }
                }
                if (response.getHistoryId() != null) {
                    newHistoryId = String.valueOf(response.getHistoryId());
                }
                pageToken = response.getNextPageToken();
            } while (pageToken != null);
        } catch (GoogleJsonResponseException exception) {
            // 404 here means the cursor is older than Gmail's history window. That is expected
            // after a long outage and is a recovery path, not an error.
            if (exception.getStatusCode() == 404) {
                log.warn("historyId {} expired; falling back to a cold start", startHistoryId);
                return Optional.empty();
            }
            throw exception;
        }

        return Optional.of(new CursorPage(added, newHistoryId));
    }

    /** Cold-start / recovery path: the most recent inbox messages, capped by configuration. */
    @Override
    public List<String> recentInboxIds(MailAccount account) throws IOException {
        ListMessagesResponse response =
                gmail(account)
                        .users()
                        .messages()
                        .list(config.getUserId())
                        .setQ("in:inbox")
                        .setMaxResults((long) config.getColdStartMaxMessages())
                        .execute();

        if (response.getMessages() == null) {
            return List.of();
        }
        return response.getMessages().stream().map(Message::getId).toList();
    }

    /** Fetches one message and flattens it to the fields triage actually uses. */
    @Override
    public FetchedMessage fetch(MailAccount account, String gmailId) throws IOException {
        Message message =
                gmail(account)
                        .users()
                        .messages()
                        .get(config.getUserId(), gmailId)
                        .setFormat("full")
                        .execute();

        Map<String, String> headers = headersOf(message);
        String body = plainTextBody(message.getPayload());
        String snippet = snippet(body, message.getSnippet());

        return new FetchedMessage(
                MailProviderType.GMAIL,
                message.getId(),
                headers.getOrDefault(HEADER_FROM.toLowerCase(Locale.ROOT), "(unknown sender)"),
                headers.getOrDefault(HEADER_SUBJECT.toLowerCase(Locale.ROOT), "(no subject)"),
                snippet,
                Instant.ofEpochMilli(
                        message.getInternalDate() != null ? message.getInternalDate() : 0L),
                headers);
    }

    /**
     * Header names are case-insensitive per RFC 5322; normalise once so lookups are predictable.
     */
    private static Map<String, String> headersOf(Message message) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return headers;
        }
        for (MessagePartHeader header : message.getPayload().getHeaders()) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        return headers;
    }

    /**
     * Depth-first search for the first {@code text/plain} part. HTML is deliberately not parsed or
     * rendered anywhere in this service — the Gmail-provided snippet is the fallback.
     */
    private static String plainTextBody(MessagePart part) {
        if (part == null) {
            return "";
        }
        if ("text/plain".equalsIgnoreCase(part.getMimeType())
                && part.getBody() != null
                && part.getBody().getData() != null) {
            return decode(part.getBody().getData());
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String found = plainTextBody(child);
                if (!found.isBlank()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static String decode(String base64Url) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String snippet(String body, String gmailSnippet) {
        String source = body.isBlank() ? Optional.ofNullable(gmailSnippet).orElse("") : body;
        String collapsed = source.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= config.getSnippetLength()
                ? collapsed
                : collapsed.substring(0, config.getSnippetLength());
    }
}
