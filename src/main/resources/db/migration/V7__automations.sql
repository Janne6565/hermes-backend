-- Automations: "when a mail like this arrives, do that."
--
-- The trigger is plain language, not a pattern. It is sent to the classifier alongside the
-- categories, and the classifier answers with the automations a mail matches in the same turn — so
-- an automation costs prompt tokens, never an extra LLM call.
--
-- Like categories, an automation never changes a message's priority. It adds an action on top of
-- whatever the triage decided: a push, an urgent push, a webhook call, or a combination.

CREATE TABLE automations (
    id            uuid PRIMARY KEY,
    name          text        NOT NULL UNIQUE,
    -- The natural-language trigger, e.g. "any mail about AWS pricing or billing".
    trigger_text  text        NOT NULL,
    -- NONE | DIRECT | IMPORTANT. DIRECT is a push that respects quiet hours, IMPORTANT is an
    -- urgent one that pierces them.
    alert         text        NOT NULL DEFAULT 'NONE',
    -- Optional; POSTed a JSON description of the mail when the automation fires.
    webhook_url   text,
    enabled       boolean     NOT NULL DEFAULT true,
    fire_count    bigint      NOT NULL DEFAULT 0,
    last_fired_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- One row per (automation, message) firing, with what each action actually did. This is the
-- screen's "recent runs" list and the only way to tell a webhook that 500s from one that was
-- never called.
CREATE TABLE automation_runs (
    id              uuid PRIMARY KEY,
    automation_id   uuid        NOT NULL REFERENCES automations (id) ON DELETE CASCADE,
    -- Nullable for test runs, which have no message. Cascades so retention takes the run with the
    -- mail it describes.
    message_id      uuid        REFERENCES messages (id) ON DELETE CASCADE,
    fired_at        timestamptz NOT NULL DEFAULT now(),
    alert_outcome   text        NOT NULL,
    webhook_outcome text        NOT NULL,
    detail          text
);

CREATE INDEX idx_automation_runs_fired_at ON automation_runs (fired_at DESC);
CREATE INDEX idx_automation_runs_message ON automation_runs (message_id);
