package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.GoogleAccountEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Connection state for the settings and onboarding screens.
 *
 * <p>Carries no token material of any kind — only whether an account is attached and which one.
 */
@Schema(description = "Whether a Google account is connected, and which")
public record GoogleAccountDto(
        boolean connected,
        @Schema(description = "The connected mailbox, when known") String email,
        Instant connectedAt,
        String scope,
        @Schema(
                        description =
                                "False when no OAuth client is configured — sign-in cannot start yet")
                boolean clientConfigured) {

    public static GoogleAccountDto connected(GoogleAccountEntity account) {
        return new GoogleAccountDto(
                true, account.getEmail(), account.getConnectedAt(), account.getScope(), true);
    }

    public static GoogleAccountDto disconnected(boolean clientConfigured) {
        return new GoogleAccountDto(false, null, null, null, clientConfigured);
    }
}
