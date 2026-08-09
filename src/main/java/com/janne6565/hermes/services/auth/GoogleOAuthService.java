package com.janne6565.hermes.services.auth;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.GoogleAccountEntity;
import com.janne6565.hermes.model.core.GoogleAccountDto;
import com.janne6565.hermes.model.exception.OAuthException;
import com.janne6565.hermes.repository.GoogleAccountRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The in-app "Sign in with Google" flow.
 *
 * <p>Replaces hand-pasting a refresh token into a sealed secret. The user clicks a button, grants
 * `gmail.readonly` once, and the refresh token is exchanged server-side and stored encrypted — the
 * token never reaches the browser.
 */
@Service
@Slf4j
public class GoogleOAuthService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo";

    /**
     * Read-only, and public so the settings screen can state the exact scope rather than a
     * hand-copied approximation of it.
     */
    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly email";

    /** A consent round-trip that takes longer than this is almost certainly abandoned. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    /**
     * Pending CSRF states. In memory rather than in Postgres on purpose: they live for minutes, and
     * losing them on a restart costs one retry of the consent screen. A restart mid-flow failing
     * closed is the correct behaviour here.
     */
    private final Map<String, Instant> pendingStates = new LinkedHashMap<>();

    private final SecureRandom random = new SecureRandom();
    private final GoogleAccountRepository accountRepository;
    private final TokenCipher tokenCipher;
    private final HermesProperties properties;
    private final GmailClientProvider gmailClientProvider;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Clock clock;

    public GoogleOAuthService(
            GoogleAccountRepository accountRepository,
            TokenCipher tokenCipher,
            HermesProperties properties,
            GmailClientProvider gmailClientProvider,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.tokenCipher = tokenCipher;
        this.properties = properties;
        this.gmailClientProvider = gmailClientProvider;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
        this.clock = clock;
    }

    /**
     * @return the Google consent URL the browser should be sent to.
     */
    public String buildAuthorizationUrl() {
        HermesProperties.Gmail config = properties.getGmail();
        if (config.getClientId().isBlank() || config.getClientSecret().isBlank()) {
            throw new OAuthException(
                    "No Google OAuth client is configured. Set the client id and secret in the "
                            + "gmail-oauth secret first — those identify the app, not the account.");
        }

        String state = newState();
        return AUTH_ENDPOINT
                + "?client_id="
                + encode(config.getClientId())
                + "&redirect_uri="
                + encode(config.getRedirectUri())
                + "&response_type=code"
                + "&scope="
                + encode(SCOPE)
                // offline + consent is what actually yields a refresh token. Without `prompt`,
                // Google returns only an access token on the second and later authorisations,
                // and the connect flow appears to succeed while storing nothing usable.
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state="
                + encode(state);
    }

    /** Exchanges the authorization code and stores the account. */
    @Transactional
    public GoogleAccountDto completeConnect(String code, String state) {
        requireValidState(state);
        if (code == null || code.isBlank()) {
            throw new OAuthException("Google did not return an authorization code");
        }

        JsonNode tokens = exchangeCode(code);
        String refreshToken = text(tokens, "refresh_token");
        if (refreshToken == null || refreshToken.isBlank()) {
            // Happens when the account previously granted access and Google decided not to
            // re-issue. `prompt=consent` above should prevent it; if it still occurs the user has
            // to revoke the app at myaccount.google.com and retry.
            throw new OAuthException(
                    "Google returned no refresh token. Revoke Hermes at "
                            + "myaccount.google.com/permissions and connect again.");
        }

        String email = fetchEmail(text(tokens, "access_token"));

        GoogleAccountEntity account =
                accountRepository
                        .findById(GoogleAccountEntity.SINGLETON_ID)
                        .orElseGet(GoogleAccountEntity::new);
        account.setId(GoogleAccountEntity.SINGLETON_ID);
        account.setEmail(email);
        account.setRefreshTokenEncrypted(tokenCipher.encrypt(refreshToken));
        account.setScope(SCOPE);
        account.setConnectedAt(Instant.now(clock));
        accountRepository.save(account);

        // Drop any cached client built from the previous token.
        gmailClientProvider.invalidate();

        log.info("Connected Google account {}", email);
        return GoogleAccountDto.connected(account);
    }

    @Transactional(readOnly = true)
    public GoogleAccountDto status() {
        return accountRepository
                .findById(GoogleAccountEntity.SINGLETON_ID)
                .map(GoogleAccountDto::connected)
                .orElseGet(
                        () ->
                                GoogleAccountDto.disconnected(
                                        !properties.getGmail().getClientId().isBlank()));
    }

    /**
     * Forgets the account locally.
     *
     * <p>This does not revoke the grant at Google — that is deliberately the user's own action at
     * myaccount.google.com, and the UI says so rather than implying more than it does.
     */
    @Transactional
    public void disconnect() {
        accountRepository.deleteById(GoogleAccountEntity.SINGLETON_ID);
        gmailClientProvider.invalidate();
        log.info("Disconnected the Google account");
    }

    /**
     * @return the decrypted refresh token, if an account is connected.
     */
    @Transactional(readOnly = true)
    public Optional<String> storedRefreshToken() {
        return accountRepository
                .findById(GoogleAccountEntity.SINGLETON_ID)
                .map(account -> tokenCipher.decrypt(account.getRefreshTokenEncrypted()));
    }

    private JsonNode exchangeCode(String code) {
        HermesProperties.Gmail config = properties.getGmail();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", config.getClientId());
        form.add("client_secret", config.getClientSecret());
        form.add("redirect_uri", config.getRedirectUri());
        form.add("grant_type", "authorization_code");

        try {
            String body =
                    restClient
                            .post()
                            .uri(TOKEN_ENDPOINT)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .body(String.class);
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            // Google's error body can echo request parameters; keep it out of the response.
            log.error("Token exchange failed: {}", exception.getMessage());
            throw new OAuthException("Google rejected the authorization code. Please try again.");
        }
    }

    /** Best effort — the connection is valid with or without a display address. */
    private String fetchEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            String body =
                    restClient
                            .get()
                            .uri(USERINFO_ENDPOINT)
                            .headers(headers -> headers.setBearerAuth(accessToken))
                            .retrieve()
                            .body(String.class);
            return text(objectMapper.readTree(body), "email");
        } catch (Exception exception) {
            log.warn("Could not read the account email: {}", exception.getMessage());
            return null;
        }
    }

    private synchronized String newState() {
        pendingStates.entrySet().removeIf(entry -> entry.getValue().isBefore(Instant.now(clock)));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pendingStates.put(state, Instant.now(clock).plus(STATE_TTL));
        return state;
    }

    /**
     * The state check is what stops an attacker from feeding us a code for <em>their</em> mailbox
     * via a forged callback. Single-use: consumed whether or not it was still valid.
     */
    private synchronized void requireValidState(String state) {
        Instant expiry = state == null ? null : pendingStates.remove(state);
        if (expiry == null || expiry.isBefore(Instant.now(clock))) {
            throw new OAuthException(
                    "The sign-in link expired or was not started here. Try again.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
