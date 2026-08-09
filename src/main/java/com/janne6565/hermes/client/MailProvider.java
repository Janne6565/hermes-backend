package com.janne6565.hermes.client;

import com.janne6565.hermes.model.core.CursorPage;
import com.janne6565.hermes.model.core.FetchedMessage;
import com.janne6565.hermes.model.core.MailProviderType;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * One mailbox service that Hermes can read.
 *
 * <p>The shape is dictated by what the sync loop already needed for Gmail, because that turned out
 * to be the general shape: an opaque resume cursor, a bounded listing for when the cursor is gone,
 * and a per-message fetch. Microsoft Graph's delta query fits it without bending — its
 * {@code @odata.deltaLink} plays the part of Gmail's {@code historyId}, and its 410 on an expired
 * delta token plays the part of Gmail's 404.
 *
 * <p>Implementations are outbound clients: they translate a provider's API into {@link
 * FetchedMessage} and nothing else. No triage logic lives here — a provider decides how to read
 * mail, never what the mail is worth.
 *
 * <p>Credentials are resolved by the implementation. Accounts become plural in phase 2 of
 * PLAN-multi-provider, at which point these methods take the account to act for; today there is
 * exactly one mailbox and passing it would be a parameter with one possible value.
 */
public interface MailProvider {

    MailProviderType type();

    /** False when no account is connected — the sync loop skips the provider entirely. */
    boolean isConnected();

    /**
     * Incremental sync from a stored cursor.
     *
     * @return the ids that arrived since {@code cursor}, plus the cursor to store next — or {@link
     *     Optional#empty()} when the provider has expired the cursor and a cold start is required.
     */
    Optional<CursorPage> messagesSince(String cursor) throws IOException;

    /** The cursor meaning "from now on", used to pin a cold start before it backfills. */
    String currentCursor() throws IOException;

    /** Bounded cold-start / recovery listing, newest first. */
    List<String> recentInboxIds() throws IOException;

    FetchedMessage fetch(String externalId) throws IOException;

    /** Deep link into this provider's web client, for the "open in…" action. */
    String deepLink(String externalId);
}
