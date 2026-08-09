package com.janne6565.hermes.services.digest;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the evening send.
 *
 * <p>It exists as its own bean for one reason: the send is three phases with very different costs —
 * a database read, an LLM call that may take a minute and a half, and a database write — and only
 * the first and last belong in a transaction. Spring's transaction proxy only applies to calls that
 * arrive from outside the bean, so splitting the phases inside {@link DigestService} would have
 * silently kept them all in one transaction. A separate caller is what actually separates them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestSender {

    private final DigestService digestService;
    private final NotificationService notificationService;
    private final HermesProperties properties;
    private final Clock clock;

    /**
     * Fires at the configured send time in the user's timezone. The cron reads the property, so
     * moving the digest is a config change and a restart, not a code change.
     */
    @Scheduled(
            cron =
                    "#{@hermesProperties.digest.sendTime.getSecond()} "
                            + "#{@hermesProperties.digest.sendTime.getMinute()} "
                            + "#{@hermesProperties.digest.sendTime.getHour()} * * *",
            zone = "#{@hermesProperties.timezone.getId()}")
    public void sendDailyDigest() {
        send(LocalDate.now(clock), false);
    }

    /**
     * Sends today's digest right now, on request.
     *
     * <p>Ignores {@code skipWhenEmpty}: that setting exists so a quiet day does not buzz the phone
     * unasked, and someone pressing the button has asked. Re-running it overwrites today's record
     * and rewrites the narrative — deliberately, because the point of the button is to see the
     * digest for the day as it stands now.
     *
     * @return the day as it now reads, including the paragraph that was just written.
     */
    public DigestDto sendNow() {
        LocalDate today = LocalDate.now(clock);
        send(today, true);
        // Re-read rather than return the in-flight object, so the caller gets exactly what the app
        // will show on its next refresh — same narrative, same delivery timestamp.
        return digestService.today();
    }

    private void send(LocalDate today, boolean force) {
        // Phase 1 — read. Short, transactional, and over before anything slow starts.
        DigestDto digest = digestService.buildForDelivery(today);

        if (!force && properties.getDigest().isSkipWhenEmpty() && digest.counts().total() == 0) {
            log.info("Nothing arrived today — skipping the digest");
            return;
        }

        // Phase 2 — narrate and push. No transaction, no connection held: neither the sidecar nor
        // ntfy touches the database, and both are allowed to be slow. Narrated exactly once, here,
        // rather than on read — the widget refreshes on every screen open, and an LLM call per
        // refresh would cost real money to reword a paragraph nobody asked to have changed.
        digest = digestService.narrate(digest);

        boolean delivered =
                notificationService.pushDigest(
                        "Digest · %d high · %d normal"
                                .formatted(digest.counts().high(), digest.counts().normal()),
                        digestService.render(digest));

        // Phase 3 — write what actually happened.
        digestService.recordDelivery(today, digest, delivered ? Instant.now(clock) : null);

        log.info(
                "Digest for {}: {} high / {} normal / {} noise (delivered={})",
                today,
                digest.counts().high(),
                digest.counts().normal(),
                digest.counts().noise(),
                delivered);
    }
}
