-- Categories: the second axis, deliberately orthogonal to priority.
--
-- Every message gets exactly one category and priority is never touched by it. That separation is
-- the whole point: a `Billing` mail can still be noise, and a category correction must not be able
-- to silence — or start pushing — anything. It is enforced structurally rather than by convention:
-- category rules live in their own table with no priority column at all, so there is no code path
-- from "recategorise this sender" to "change what interrupts me".
--
-- `Uncategorised` is a real row, not a NULL. A message with no rule hit and no model verdict has
-- to land somewhere countable, and "every message gets exactly one" only reads as true on the
-- categories screen if the leftovers are one of the rows.

CREATE TABLE categories (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL UNIQUE,
    color      text        NOT NULL,
    -- Seeded rows cannot be deleted; the user's own can.
    builtin    boolean     NOT NULL DEFAULT false,
    -- Exactly one row is the landing place for everything unresolved.
    fallback   boolean     NOT NULL DEFAULT false,
    position   int         NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_categories_single_fallback ON categories (fallback) WHERE fallback;

-- Deliberately not a column on `rules`: a priority rule and a category rule are different things
-- with different blast radii, and keeping them apart means the tiered priority engine — the piece
-- that decides what wakes the user up — is untouched by this feature.
CREATE TABLE category_rules (
    id          uuid PRIMARY KEY,
    category_id uuid        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    type        text        NOT NULL,
    pattern     text        NOT NULL,
    source      text        NOT NULL,
    hits        bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Global, not per category: one sender belongs to one category, so a correction updates the
    -- existing row instead of leaving two rules fighting over the same address.
    CONSTRAINT uq_category_rules_type_pattern UNIQUE (type, pattern)
);

CREATE INDEX idx_category_rules_category ON category_rules (category_id);

ALTER TABLE messages ADD COLUMN category_id uuid REFERENCES categories (id) ON DELETE SET NULL;
ALTER TABLE messages ADD COLUMN category_source text;
-- Model confidence, 0..1. NULL for everything a rule or the user settled — those are not guesses.
ALTER TABLE messages ADD COLUMN category_confidence real;

-- The classifier's runner-up. Only useful while the message is in the "needs a call" queue, but it
-- is the difference between offering the user two plausible chips and a blank dropdown.
ALTER TABLE messages ADD COLUMN category_alt_id uuid REFERENCES categories (id) ON DELETE SET NULL;

-- The correction trail lives on the message rather than in a history table so that retention
-- deletes it along with everything else about that mail — a "was Infrastructure" line outliving the
-- message it describes would be a small privacy leak with no reader.
ALTER TABLE messages ADD COLUMN category_previous_id uuid REFERENCES categories (id) ON DELETE SET NULL;
ALTER TABLE messages ADD COLUMN category_corrected_at timestamptz;

CREATE INDEX idx_messages_category ON messages (category_id);

-- ---------------------------------------------------------------------------------------------
-- seed
-- ---------------------------------------------------------------------------------------------
--
-- Colours come from the mockup with one substitution: the mockup paints Alerts in #d9603f, which
-- is exactly --color-broken. Red in this product means "mail is not being read", so the category
-- takes the mockup's desaturated clay instead and the red stays reserved.

INSERT INTO categories (id, name, color, builtin, fallback, position) VALUES
    (gen_random_uuid(), 'Alerts',         '#c98b74', true, false, 1),
    (gen_random_uuid(), 'Infrastructure', '#c9a227', true, false, 2),
    (gen_random_uuid(), 'Billing',        '#b8934a', true, false, 3),
    (gen_random_uuid(), 'People',         '#7ba05b', true, false, 4),
    (gen_random_uuid(), 'Reports',        '#6b8fa8', true, false, 5),
    (gen_random_uuid(), 'Admin',          '#8a857b', true, false, 6),
    (gen_random_uuid(), 'Newsletters',    '#5a5a72', true, false, 7),
    (gen_random_uuid(), 'Uncategorised',  '#3a3936', true, true,  8);

-- Only signals that are facts about the sender or the envelope, in the spirit of V2. Anything that
-- needs judgement (is this invoice Billing or Infrastructure?) is left to the classifier, which is
-- allowed to be unsure and ask.
INSERT INTO category_rules (id, category_id, type, pattern, source, hits)
SELECT gen_random_uuid(), c.id, r.type, r.pattern, 'MANUAL', 0
FROM (VALUES
    ('Alerts',         'SENDER', '*grafana*'),
    ('Alerts',         'SENDER', '*signoz*'),
    ('Alerts',         'SENDER', '*alertmanager*'),
    ('Alerts',         'SENDER', '*prometheus*'),
    ('Infrastructure', 'SENDER', '*hetzner*'),
    ('Infrastructure', 'SENDER', '*cloudflare*'),
    ('Infrastructure', 'SENDER', '*argocd*'),
    ('Infrastructure', 'SENDER', '*cert-manager*'),
    ('Newsletters',    'HEADER', 'List-Unsubscribe')
) AS r (category, type, pattern)
         JOIN categories c ON c.name = r.category;

-- Everything already on record predates the feature. Parking it in the fallback with source NONE
-- keeps the screen's arithmetic honest — these were never classified, and claiming otherwise would
-- inflate the "settled by rule" share on day one.
UPDATE messages
SET category_id     = (SELECT id FROM categories WHERE fallback),
    category_source = 'NONE'
WHERE category_id IS NULL;
