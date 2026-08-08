-- Hermes initial schema.
--
-- Priorities, rule types and severities are stored as text rather than Postgres enums so a new
-- value is a code change, not a migration + type alter. The application validates them.

CREATE TABLE messages (
    id            uuid PRIMARY KEY,
    gmail_id      text        NOT NULL UNIQUE,
    sender        text        NOT NULL,
    subject       text,
    snippet       varchar(1000),
    received_at   timestamptz NOT NULL,
    priority      text        NOT NULL,
    summary       text,
    reason        text,
    classified_by text        NOT NULL,
    notified_at   timestamptz,
    dismissed_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- The digest and the inbox both scan a day at a time; retention deletes by the same column.
CREATE INDEX idx_messages_received_at ON messages (received_at DESC);
-- Partial index for the inbox's leading query: high-priority items still open.
CREATE INDEX idx_messages_open_high ON messages (received_at DESC)
    WHERE priority = 'HIGH' AND dismissed_at IS NULL;
-- The nightly retry job scans exclusively for fallback rows.
CREATE INDEX idx_messages_classified_by ON messages (classified_by);

CREATE TABLE rules (
    id         uuid PRIMARY KEY,
    type       text        NOT NULL,
    pattern    text        NOT NULL,
    priority   text        NOT NULL,
    enabled    boolean     NOT NULL DEFAULT true,
    source     text        NOT NULL DEFAULT 'MANUAL',
    hits       bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- One rule per (type, pattern): the feedback path upserts against this.
    CONSTRAINT uq_rules_type_pattern UNIQUE (type, pattern)
);

CREATE INDEX idx_rules_enabled ON rules (enabled) WHERE enabled;

CREATE TABLE alert_events (
    id          uuid PRIMARY KEY,
    source      text        NOT NULL,
    severity    text        NOT NULL,
    title       text        NOT NULL,
    app_name    text,
    fingerprint text,
    payload     jsonb,
    received_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    notified    boolean     NOT NULL DEFAULT false
);

CREATE INDEX idx_alert_events_received_at ON alert_events (received_at DESC);
-- A fingerprint identifies one *open* alert instance; once resolved, the same fingerprint may
-- legitimately fire again, so the uniqueness only applies while resolved_at is null.
CREATE UNIQUE INDEX uq_alert_events_open_fingerprint ON alert_events (fingerprint)
    WHERE resolved_at IS NULL AND fingerprint IS NOT NULL;

CREATE TABLE sync_state (
    id         integer PRIMARY KEY,
    history_id text,
    last_sync  timestamptz,
    last_error varchar(2000)
);

CREATE TABLE digests (
    id          uuid PRIMARY KEY,
    digest_date date        NOT NULL UNIQUE,
    content     jsonb       NOT NULL,
    sent_at     timestamptz
);
