package com.janne6565.hermes.services.auth;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.janne6565.hermes.client.MailAccount;
import com.janne6565.hermes.configuration.HermesProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds and caches a Gmail API handle per connected account.
 *
 * <p>Cached because constructing one sets up an HTTP transport and a credential refresher; rebuilt
 * when the stored token changes, which is what makes a reconnect take effect without a restart.
 * Keyed by account id and validated against the token so a re-consent cannot leave a stale
 * credential in place.
 */
@Component
@RequiredArgsConstructor
public class GmailClientProvider {

    private final HermesProperties properties;
    private final Map<UUID, CachedClient> cache = new ConcurrentHashMap<>();

    public Gmail forAccount(MailAccount account) throws IOException {
        CachedClient cached = cache.get(account.id());
        if (cached != null && cached.refreshToken().equals(account.refreshToken())) {
            return cached.client();
        }
        Gmail client = build(account.refreshToken());
        cache.put(account.id(), new CachedClient(account.refreshToken(), client));
        return client;
    }

    /** Drops a cached handle — called on disconnect so a removed account leaves nothing behind. */
    public void invalidate(UUID accountId) {
        cache.remove(accountId);
    }

    private Gmail build(String refreshToken) throws IOException {
        HermesProperties.Gmail config = properties.getGmail();
        GoogleCredentials credentials =
                UserCredentials.newBuilder()
                        .setClientId(config.getClientId())
                        .setClientSecret(config.getClientSecret())
                        .setRefreshToken(refreshToken)
                        .build()
                        // The scope stays readonly. This service reads mail; it never writes it.
                        .createScoped(List.of(GmailScopes.GMAIL_READONLY));

        try {
            return new Gmail.Builder(
                            GoogleNetHttpTransport.newTrustedTransport(),
                            GsonFactory.getDefaultInstance(),
                            new HttpCredentialsAdapter(credentials))
                    .setApplicationName("hermes")
                    .build();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("Could not build the Gmail transport", exception);
        }
    }

    private record CachedClient(String refreshToken, Gmail client) {}
}
