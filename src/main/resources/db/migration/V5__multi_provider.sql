-- Make mail accounts plural, ahead of Outlook support.
--
-- V5, not V4: V4 (alert acknowledge/snooze) landed first. Nothing here touches alert_events, so
-- the two are independent.
--
-- One migration rather than three so a partial apply cannot leave a half-renamed messages table
-- next to a dropped google_account.
--
-- Nothing here re-encrypts anything: the refresh token ciphertext is copied across as-is, under
-- the same AES key, so the connected mailbox survives without a reconnect.

-- ---------------------------------------------------------------------------------------------
-- messages become provider-scoped
-- ---------------------------------------------------------------------------------------------

ALTER TABLE messages ADD COLUMN provider text NOT NULL DEFAULT 'GMAIL';
ALTER TABLE messages RENAME COLUMN gmail_id TO external_id;

-- The old unique was on gmail_id alone. External ids are only unique *within* a provider, so the
-- replacement is composite — two providers may legitimately hand out the same id string.
ALTER TABLE messages DROP CONSTRAINT messages_gmail_id_key;
ALTER TABLE messages ADD CONSTRAINT uq_messages_provider_external UNIQUE (provider, external_id);

-- The default existed only to backfill the rows already in the table. Drop it so a future insert
-- has to say which provider it came from rather than silently claiming Gmail.
ALTER TABLE messages ALTER COLUMN provider DROP DEFAULT;

-- ---------------------------------------------------------------------------------------------
-- accounts: one pinned row -> a table
-- ---------------------------------------------------------------------------------------------

CREATE TABLE mail_account (
    id                      uuid PRIMARY KEY,
    provider                text        NOT NULL,
    email                   text,
    refresh_token_encrypted text        NOT NULL,
    scope                   text        NOT NULL,
    connected_at            timestamptz NOT NULL DEFAULT now(),
    -- One account per address per provider. Reconnecting the same mailbox updates in place
    -- instead of quietly accumulating duplicates that would each poll the same inbox.
    CONSTRAINT uq_mail_account_provider_email UNIQUE (provider, email)
);

INSERT INTO mail_account (id, provider, email, refresh_token_encrypted, scope, connected_at)
SELECT gen_random_uuid(), 'GMAIL', email, refresh_token_encrypted, scope, connected_at
FROM google_account;

-- ---------------------------------------------------------------------------------------------
-- cursors: one per account, and deliberately not on mail_account
-- ---------------------------------------------------------------------------------------------
--
-- The cursor is written on every poll; the encrypted refresh token is written about twice in its
-- life. Keeping them in separate tables means no routine write path ever touches the credential
-- row, so a bug in the poll loop cannot clobber a token.

CREATE TABLE account_sync_state (
    account_id uuid PRIMARY KEY REFERENCES mail_account (id) ON DELETE CASCADE,
    cursor     text,
    last_sync  timestamptz,
    last_error text
);

INSERT INTO account_sync_state (account_id, cursor, last_sync, last_error)
SELECT a.id, s.history_id, s.last_sync, s.last_error
FROM mail_account a
         CROSS JOIN sync_state s
WHERE a.provider = 'GMAIL';

DROP TABLE google_account;
DROP TABLE sync_state;

-- The partial index for the inbox's leading query referenced no renamed column, so it survives
-- the rename untouched. This one did not exist before and pays for the per-account poll.
CREATE INDEX idx_messages_provider ON messages (provider);
