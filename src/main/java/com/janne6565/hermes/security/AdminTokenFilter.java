package com.janne6565.hermes.security;

import com.janne6565.hermes.configuration.HermesProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single-user access control.
 *
 * <p>Hermes reads someone's mail and, since the in-app connect flow exists, can be pointed at a
 * mailbox. Neither may be reachable by whoever finds the hostname, so everything under
 * {@code /api} requires the admin token.
 *
 * <p>Two endpoints are deliberately exempt, each because it carries its own stronger check:
 *
 * <ul>
 *   <li>{@code POST /api/v1/events/alert} — Grafana and SigNoz can only send a static header, and
 *       it has its own shared secret compared in constant time.
 *   <li>{@code GET /api/v1/auth/google/callback} — the browser arrives from Google's redirect and
 *       cannot carry a header; the single-use {@code state} parameter is what binds it to a flow
 *       this service actually started.
 * </ul>
 *
 * <p>This is intentionally the smallest thing that closes the hole. The longer-term answer is
 * putting the app behind Authentik like the rest of the house apps.
 */
@Component
@Order(1)
@Slf4j
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Hermes-Token";
    public static final String COOKIE = "hermes_token";

    private final String expectedToken;

    public AdminTokenFilter(HermesProperties properties) {
        this.expectedToken = properties.getAdminToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn(
                    "hermes.admin-token is not set — the API is UNAUTHENTICATED. Acceptable for "
                            + "local development only; set it before exposing this service.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api")) {
            // Actuator probes, the OpenAPI document and the static frontend are not gated here.
            return true;
        }
        return path.equals("/api/v1/events/alert") || path.equals("/api/v1/auth/google/callback");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (expectedToken == null || expectedToken.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (!isAuthorised(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"detail\":\"Missing or invalid admin token\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAuthorised(HttpServletRequest request) {
        return presentedToken(request).filter(this::matches).isPresent();
    }

    /**
     * Header first (for scripts and the Janus widget), cookie second (so the browser keeps the
     * session after the token is entered once).
     */
    private Optional<String> presentedToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return Optional.of(header);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** Constant-time, so the endpoint cannot be used to recover the token byte by byte. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
