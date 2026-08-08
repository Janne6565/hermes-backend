package com.janne6565.hermes.services.auth;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.GoogleAccountEntity;
import com.janne6565.hermes.repository.GoogleAccountRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Gmail API client from whichever refresh token is currently in force.
 *
 * <p>This exists instead of a startup {@code @Bean} because the account is now connected at
 * runtime through the in-app sign-in flow: there may be no token when the pod starts, and the
 * token can change without a restart. The client is cached and invalidated on connect/disconnect
 * rather than rebuilt per poll.
 *
 * <p>A refresh token configured through {@code hermes.gmail.refresh-token} still works and takes
 * second place — it is the bootstrap/escape hatch for a cluster where the UI is not reachable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GmailClientProvider {

    private final GoogleAccountRepository accountRepository;
    private final TokenCipher tokenCipher;
    private final HermesProperties properties;

    private volatile Gmail cached;
    private volatile String cachedFor;

    /** @return the Gmail client, or empty when no account is connected. */
    @Transactional(readOnly = true)
    public Optional<Gmail> current() {
        Optional<String> refreshToken = activeRefreshToken();
        if (refreshToken.isEmpty()) {
            return Optional.empty();
        }

        String token = refreshToken.get();
        Gmail existing = cached;
        if (existing != null && token.equals(cachedFor)) {
            return Optional.of(existing);
        }

        try {
            Gmail client = build(token);
            cached = client;
            cachedFor = token;
            return Optional.of(client);
        } catch (Exception exception) {
            log.error("Could not build the Gmail client: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public boolean isConnected() {
        return activeRefreshToken().isPresent();
    }

    /** Drops the cached client so the next poll picks up a newly connected account. */
    public void invalidate() {
        cached = null;
        cachedFor = null;
    }

    private Optional<String> activeRefreshToken() {
        Optional<String> stored =
                accountRepository
                        .findById(GoogleAccountEntity.SINGLETON_ID)
                        .map(account -> tokenCipher.decrypt(account.getRefreshTokenEncrypted()));
        if (stored.isPresent()) {
            return stored;
        }
        String configured = properties.getGmail().getRefreshToken();
        return configured == null || configured.isBlank() ? Optional.empty() : Optional.of(configured);
    }

    private Gmail build(String refreshToken) throws Exception {
        HermesProperties.Gmail config = properties.getGmail();
        GoogleCredentials credentials =
                UserCredentials.newBuilder()
                        .setClientId(config.getClientId())
                        .setClientSecret(config.getClientSecret())
                        .setRefreshToken(refreshToken)
                        .build()
                        // The scope stays readonly. This service reads mail; it never writes it.
                        .createScoped(List.of(GmailScopes.GMAIL_READONLY));

        return new Gmail.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                .setApplicationName("hermes-mail-triage")
                .build();
    }
}
