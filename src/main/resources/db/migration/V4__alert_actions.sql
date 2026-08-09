-- Acknowledge and snooze for alerts.
--
-- Both are operator state, deliberately separate from resolved_at: only the alert source may
-- decide an alert is resolved. Acknowledging says "I have seen this", snoozing says "stop pushing
-- until then" — neither claims the underlying problem went away.

ALTER TABLE alert_events ADD COLUMN acknowledged_at TIMESTAMPTZ;
ALTER TABLE alert_events ADD COLUMN snoozed_until TIMESTAMPTZ;

-- Snooze is enforced per fingerprint on intake, so that lookup must not table-scan.
CREATE INDEX idx_alert_events_fingerprint_snoozed
    ON alert_events (fingerprint, snoozed_until)
    WHERE snoozed_until IS NOT NULL;
