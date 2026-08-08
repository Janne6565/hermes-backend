package com.janne6565.hermes.services.notification;

import com.janne6565.hermes.client.NtfyClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.entity.MessageEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The single place that decides whether the phone buzzes.
 *
 * <p>Two gates sit in front of every push — shadow mode and quiet hours — and both are checked here
 * rather than at the call sites, so there is exactly one code path that can interrupt the user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final String GMAIL_LINK = "https://mail.google.com/mail/u/0/#inbox/";

    private final NtfyClient ntfyClient;
    private final HermesProperties properties;
    private final Clock clock;

    /**
     * Pushes a high-priority mail. Sets {@code notified_at} only when ntfy actually accepted it.
     */
    public void pushHighPriorityMail(MessageEntity message) {
        if (properties.isShadowMode()) {
            log.info(
                    "Shadow mode: would have pushed '{}' from {}",
                    message.getSubject(),
                    message.getSender());
            return;
        }
        if (inQuietHours() && !isSecurityRelated(message)) {
            log.info("Quiet hours: holding '{}' for the morning release", message.getSubject());
            return;
        }

        boolean delivered =
                ntfyClient.publish(
                        NtfyClient.Notification.urgent(
                                displayName(message.getSender()),
                                "%s%n%n%s"
                                        .formatted(
                                                message.getSubject(),
                                                nullSafe(message.getReason())),
                                "envelope",
                                GMAIL_LINK + message.getGmailId()));

        if (delivered) {
            message.setNotifiedAt(Instant.now(clock));
        }
    }

    /** Pushes an infrastructure alert. Distinct tag so it reads differently on the lock screen. */
    public boolean pushAlert(AlertEventEntity alert) {
        if (properties.isShadowMode()) {
            log.info("Shadow mode: would have pushed alert '{}'", alert.getTitle());
            return false;
        }
        // Infrastructure alerts ignore quiet hours by design: a node down at 03:00 is exactly the
        // case the user wants woken for. Mail does not get this exemption.
        return ntfyClient.publish(
                NtfyClient.Notification.urgent(
                        "%s · %s".formatted(alert.getSource().wire(), nullSafe(alert.getAppName())),
                        alert.getTitle(),
                        "rotating_light",
                        null));
    }

    /** Sends the daily digest at default priority — informative, not interrupting. */
    public boolean pushDigest(String title, String body) {
        if (properties.isShadowMode()) {
            log.info("Shadow mode: would have pushed the digest");
            return false;
        }
        return ntfyClient.publish(NtfyClient.Notification.normal(title, body, "newspaper"));
    }

    /**
     * Quiet hours normally wrap midnight (23:00 → 07:30), so the comparison has to handle a window
     * whose start is after its end.
     */
    boolean inQuietHours() {
        HermesProperties.QuietHours quiet = properties.getQuietHours();
        if (!quiet.isEnabled()) {
            return false;
        }
        LocalTime now = LocalTime.now(clock);
        LocalTime start = quiet.getStart();
        LocalTime end = quiet.getEnd();
        return start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * Security mail (2FA codes, password resets, new-device sign-ins) is time-critical enough to
     * pierce quiet hours when the user has opted into that.
     */
    private boolean isSecurityRelated(MessageEntity message) {
        if (!properties.getQuietHours().isAllowSecurityAlerts()) {
            return false;
        }
        String haystack =
                (nullSafe(message.getSubject()) + " " + nullSafe(message.getReason()))
                        .toLowerCase(java.util.Locale.ROOT);
        return haystack.contains("security")
                || haystack.contains("sign-in")
                || haystack.contains("sign in")
                || haystack.contains("password")
                || haystack.contains("verification")
                || haystack.contains("2fa");
    }

    /** {@code Dr. Anna Weber <weber@uni-potsdam.de>} → {@code Dr. Anna Weber}. */
    private static String displayName(String sender) {
        int open = sender.indexOf('<');
        String name = open > 0 ? sender.substring(0, open).trim() : sender.trim();
        return name.replaceAll("^\"|\"$", "");
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
