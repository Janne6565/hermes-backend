-- The connected Google account, established through the in-app "Sign in with Google" flow
-- instead of a hand-pasted refresh token.
--
-- The refresh token is stored encrypted (AES-GCM, key from the hermes-app-key secret) rather than
-- in plaintext: it is an account-level credential, and a database dump or a Velero snapshot must
-- not be enough to read someone's mail. Single row, same pinned-id pattern as sync_state.

CREATE TABLE google_account (
    id                      integer PRIMARY KEY,
    email                   text,
    -- Base64 of nonce || ciphertext. Never logged, never returned by the API.
    refresh_token_encrypted text        NOT NULL,
    scope                   text        NOT NULL,
    connected_at            timestamptz NOT NULL DEFAULT now()
);
