package com.janne6565.hermes.client;

import com.janne6565.hermes.model.core.MailProviderType;
import java.util.UUID;

/**
 * A mailbox a provider can act for, with the refresh token already decrypted.
 *
 * <p>Deliberately **not** in {@code model/core}: that package is DTO-land and gets serialised onto
 * the wire, and this carries a live credential. Decryption happens once, in the account service, so
 * provider implementations never touch the cipher or the encrypted column.
 *
 * @param refreshToken plaintext — never log this, never return it from a controller
 */
public record MailAccount(UUID id, MailProviderType provider, String email, String refreshToken) {

    /** Keeps the token out of logs and stack traces if this is ever interpolated by accident. */
    @Override
    public String toString() {
        return "MailAccount(id=%s, provider=%s, email=%s)".formatted(id, provider, email);
    }
}
