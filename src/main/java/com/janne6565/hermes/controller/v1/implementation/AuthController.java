package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.controller.v1.schema.AuthApi;
import com.janne6565.hermes.model.core.GoogleAccountDto;
import com.janne6565.hermes.services.auth.GoogleOAuthService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AuthController implements AuthApi {

    private final GoogleOAuthService googleOAuthService;
    private final HermesProperties properties;

    @Override
    public ResponseEntity<GoogleAccountDto> status() {
        return ResponseEntity.ok(googleOAuthService.status());
    }

    @Override
    public ResponseEntity<AuthorizationUrl> start() {
        return ResponseEntity.ok(new AuthorizationUrl(googleOAuthService.buildAuthorizationUrl()));
    }

    @Override
    public ResponseEntity<Void> callback(String code, String state, String error) {
        // The browser lands here from Google, so failures redirect with a message rather than
        // rendering a JSON problem document at the user.
        if (error != null && !error.isBlank()) {
            return redirect(failure("Google returned: " + error));
        }
        try {
            googleOAuthService.completeConnect(code, state);
            return redirect(properties.getGmail().getPostConnectRedirect());
        } catch (RuntimeException exception) {
            log.warn("Google connect failed: {}", exception.getMessage());
            return redirect(failure(exception.getMessage()));
        }
    }

    @Override
    public ResponseEntity<Void> disconnect() {
        googleOAuthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    private String failure(String message) {
        return "/settings?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }
}
