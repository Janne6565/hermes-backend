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
 * mailbox. Neither may be reachable by whoever finds the hostname, so everything under {@code /api}
 * has to be attributable to the one person allowed to be here.
 *
 * <p>Two ways in, for two different callers:
 *
 * <ul>
 *   <li><b>Authentik.</b> A browser is stopped at the ingress by a Traefik forward-auth middleware
 *       and only gets past it with an Authentik session in the {@code hermes-users} group. Traefik
 *       then stamps {@code X-authentik-username} onto the request from the outpost's response —
 *       <em>overwriting</em> whatever the client sent under that name, which is what makes the
 *       header proof rather than a claim. This is the normal path and the reason the app has no
 *       login screen of its own.
 *   <li><b>The admin token.</b> For callers with no browser and no session — scripts, and one day
 *       the Janus widget polling the digest. Presented as {@code X-Hermes-Token}. Note that this
 *       only gets anywhere on a router without the forward-auth middleware, which runs earlier;
 *       such a caller needs its path on the {@code hermes-public} Ingress, where this filter still
 *       demands the token.
 * </ul>
 *
 * <p>The header is only trustworthy because of how the routers are cut (see {@code
 * hermes-deployment/overlays/main/ingress.yaml}): the forward-auth middleware sits on the router
 * that serves every path this filter guards, and the one router without it serves only the two
 * paths below — which this filter skips anyway, so a spoofed header there reaches nothing it did
 * not already have.
 *
 * <p>Those two exemptions, each because it carries its own stronger check:
 *
 * <ul>
 *   <li>{@code POST /api/v1/events/alert} — Grafana and SigNoz can only send a static header, and
 *       it has its own shared secret compared in constant time.
 *   <li>{@code GET /api/v1/auth/google/callback} — the browser arrives from Google's redirect and
 *       cannot carry a header; the single-use {@code state} parameter is what binds it to a flow
 *       this service actually started.
 * </ul>
 */
@Component
@Order(1)
@Slf4j
public class ApiAccessFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Hermes-Token";
    public static final String COOKIE = "hermes_token";

    /**
     * Set by Traefik from the Authentik outpost's response. Presence is the whole check: the
     * outpost only answers 200 for a session that already passed the {@code hermes-users} policy
     * binding, so there is no group left to re-evaluate here.
     */
    public static final String AUTHENTIK_USER_HEADER = "X-authentik-username";

    private final String expectedToken;

    public ApiAccessFilter(HermesProperties properties) {
        this.expectedToken = properties.getAdminToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn(
                    "hermes.admin-token is not set — only Authentik forward auth is gating this "
                            + "API, and nothing at all when run outside the cluster. Acceptable "
                            + "for local development only.");
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

        if (arrivedThroughAuthentik(request)) {
            chain.doFilter(request, response);
            return;
        }

        if (expectedToken == null || expectedToken.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (!isAuthorised(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write("{\"status\":401,\"detail\":\"Missing or invalid credentials\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean arrivedThroughAuthentik(HttpServletRequest request) {
        String user = request.getHeader(AUTHENTIK_USER_HEADER);
        return user != null && !user.isBlank();
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
