-- Baseline rules so phase 1 is useful before the classifier exists.
--
-- Only the two universally-safe classes are seeded here: monitoring senders (which must never be
-- missed) and the List-Unsubscribe header (which is the single highest-yield noise signal).
-- Anything person- or domain-specific belongs in the rules UI, not in a migration.

INSERT INTO rules (id, type, pattern, priority, enabled, source, hits, created_at) VALUES
    (gen_random_uuid(), 'HEADER', 'List-Unsubscribe', 'NOISE',  true, 'MANUAL', 0, now()),
    (gen_random_uuid(), 'HEADER', 'Auto-Submitted: auto-generated', 'NORMAL', true, 'MANUAL', 0, now()),
    (gen_random_uuid(), 'SENDER', 'security@github.com', 'HIGH', true, 'MANUAL', 0, now())
ON CONFLICT (type, pattern) DO NOTHING;
