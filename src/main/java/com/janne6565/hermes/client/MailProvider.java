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
 * <p>Every method acts for a specific {@link MailAccount}, which arrives with its refresh token
 * already decrypted — implementations never touch the cipher or the encrypted column.
 */
public interface MailProvider {

    MailProviderType type();

    /**
     * Incremental sync from a stored cursor.
     *
     * @return the ids that arrived since {@code cursor}, plus the cursor to store next — or {@link
     *     Optional#empty()} when the provider has expired the cursor and a cold start is required.
     */
    Optional<CursorPage> messagesSince(MailAccount account, String cursor) throws IOException;

    /** The cursor meaning "from now on", used to pin a cold start before it backfills. */
    String currentCursor(MailAccount account) throws IOException;

    /** Bounded cold-start / recovery listing, newest first. */
    List<String> recentInboxIds(MailAccount account) throws IOException;

    FetchedMessage fetch(MailAccount account, String externalId) throws IOException;

    /** Deep link into this provider's web client, for the "open in…" action. */
    default String deepLink(String externalId) {
        return type().deepLink(externalId);
    }
}
