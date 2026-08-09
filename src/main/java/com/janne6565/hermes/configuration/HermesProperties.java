package com.janne6565.hermes.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Every knob the service has, bound once at startup so a missing secret fails the pod rather than
 * the first poll.
 */
// @Component rather than @EnableConfigurationProperties so the bean is named `hermesProperties`
// — the scheduling SpEL expressions (`#{@hermesProperties...}`) resolve it by that name.
@Component
@ConfigurationProperties(prefix = "hermes")
@Validated
@Getter
@Setter
public class HermesProperties {

    @Valid @NotNull private Gmail gmail = new Gmail();
    @Valid @NotNull private Sidecar sidecar = new Sidecar();
    @Valid @NotNull private Ntfy ntfy = new Ntfy();
    @Valid @NotNull private Digest digest = new Digest();
    @Valid @NotNull private Alerts alerts = new Alerts();
    @Valid @NotNull private Categories categories = new Categories();
    @Valid @NotNull private Retention retention = new Retention();
    @Valid @NotNull private QuietHours quietHours = new QuietHours();

    /** The user's timezone. Drives the digest cron, quiet hours and every rendered date. */
    @NotNull private ZoneId timezone = ZoneId.of("Europe/Berlin");

    /**
     * Base64-encoded 32-byte AES key protecting the stored Google refresh token. Required —
     * generate with {@code openssl rand -base64 32}. Changing it makes existing stored tokens
     * unreadable and forces a re-connect.
     */
    private String encryptionKey = "";

    /**
     * Single-user admin token guarding every endpoint except the alert webhook (which has its own
     * shared secret) and the OAuth callback (which is bound by its state parameter).
     *
     * <p>Unset means the API is open. That is fine for local development and wrong in the cluster,
     * so the service logs a loud warning at startup rather than failing — a locked-out operator
     * with no way to sign in is a worse outcome than a warning.
     */
    private String adminToken = "";

    /**
     * Shadow mode: classify and store everything, push nothing. Phase 3 of the rollout runs here
     * for a few days so the labels can be reviewed before the phone starts buzzing.
     */
    private boolean shadowMode = true;

    @Getter
    @Setter
    public static class Gmail {
        // Not @NotBlank: an unset credential must leave the service startable so tests, shadow
        // runs and rules-only mode work. GmailClientConfiguration is conditional on the refresh
        // token instead, and the health screen reports the mailbox as never-synced.
        private String clientId = "";
        private String clientSecret = "";
        private String refreshToken = "";

        /** The mailbox to poll. {@code me} resolves to the token's own account. */
        @NotBlank private String userId = "me";

        /**
         * OAuth redirect target. Must match a redirect URI registered on the Google client exactly,
         * including scheme and trailing path.
         */
        @NotBlank private String redirectUri = "http://localhost:8080/api/v1/auth/google/callback";

        /** Where the browser is sent after the callback completes — the app's settings screen. */
        @NotBlank private String postConnectRedirect = "/settings?connected=1";

        /** Poll interval. 180s keeps well inside the Gmail API quota for a single mailbox. */
        @NotNull private Duration pollInterval = Duration.ofSeconds(180);

        /**
         * Cap on a single cold-start backfill, so a lost historyId can't pull the whole mailbox.
         */
        @Min(1) private int coldStartMaxMessages = 100;

        /**
         * Characters of plaintext body sent to the classifier. Data minimisation, not a display
         * cap.
         */
        @Min(0) private int snippetLength = 500;
    }

    @Getter
    @Setter
    public static class Sidecar {
        /**
         * Intra-pod only. The sidecar holds an account-level Claude credential and must never be
         * reachable off the pod — no Service, no Ingress.
         */
        @NotBlank private String baseUrl = "http://127.0.0.1:8081";

        /** Classification is a single Haiku turn; 30s is generous and bounds the poll loop. */
        @NotNull private Duration timeout = Duration.ofSeconds(30);

        /** When false the pipeline is rules-only — useful for phase 1 and for incident response. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Categories {
        /**
         * Below this the classifier's category is treated as a question rather than an answer: the
         * message still gets the guessed category, but it also lands in the "needs a call" queue.
         *
         * <p>Note what this does *not* affect — priority. A message the classifier was unsure about
         * topically is still routed on its priority verdict, so a low threshold cannot make mail go
         * quiet while it waits for the user.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") private float confidenceThreshold = 0.7f;

        /** How many unsure messages the screen offers at once. A queue nobody finishes is noise. */
        @Min(1) private int unsureLimit = 12;

        /** Reporting window for the shares and the classification mix. */
        @Min(1) private int windowDays = 7;

        /**
         * Ask the classifier for a category even when a hard rule already settled the priority.
         *
         * <p>On, because roughly a third of this mailbox never reaches the classifier and would
         * otherwise sit in the fallback bucket for good. It is the one knob that raises the LLM
         * call rate — turn it off to restore the rules-are-free cost profile and accept that
         * rule-settled mail is categorised only by category rules.
         */
        private boolean classifyRuleHits = true;
    }

    @Getter
    @Setter
    public static class Ntfy {
        @NotBlank private String baseUrl = "https://ntfy.jannekeipert.de";
        @NotBlank private String topic = "janus-mail";

        /** Bearer token for the publishing user. The server is deny-all by default. */
        private String token = "";

        @NotNull private Duration timeout = Duration.ofSeconds(10);
    }

    @Getter
    @Setter
    public static class Digest {
        @NotNull private LocalTime sendTime = LocalTime.of(18, 0);

        /** Include the noise section (counts and a category breakdown) in the pushed digest. */
        private boolean includeNoise = true;

        /**
         * Skip the push entirely on a day where nothing arrived, rather than sending three zeroes.
         */
        private boolean skipWhenEmpty = false;
    }

    @Getter
    @Setter
    public static class Alerts {
        /**
         * Shared secret expected in {@code X-Hermes-Token} on the webhook intake. Unset means the
         * endpoint rejects everything — it fails closed, so this is safe to leave empty.
         */
        private String webhookSecret = "";

        /**
         * How long a critical alert must keep firing before it earns a push. Absorbs the flapping
         * probes that would otherwise be the loudest thing on the phone.
         */
        @NotNull private Duration pushDelay = Duration.ZERO;

        /**
         * Where an alert came from, so the UI can link back to the tool that owns it.
         *
         * <p>Hermes deliberately holds no alert history or dashboards of its own — it decides what
         * is worth a push and hands you back to Grafana or SigNoz for everything else. Blank means
         * no link is offered rather than a broken one.
         */
        private String grafanaUrl = "";

        private String signozUrl = "";

        /** Default snooze, used when the caller does not say how long. */
        @NotNull private Duration defaultSnooze = Duration.ofHours(4);
    }

    @Getter
    @Setter
    public static class Retention {
        /** Classified messages older than this are deleted nightly. */
        @Min(1) private int messageDays = 90;
    }

    @Getter
    @Setter
    public static class QuietHours {
        private boolean enabled = false;

        @NotNull private LocalTime start = LocalTime.of(23, 0);
        @NotNull private LocalTime end = LocalTime.of(7, 30);

        /** Security alerts (login attempts, password resets) are let through regardless. */
        private boolean allowSecurityAlerts = true;
    }
}
