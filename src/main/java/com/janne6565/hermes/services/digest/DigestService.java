package com.janne6565.hermes.services.digest;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.entity.DigestEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.exception.DigestNotFoundException;
import com.janne6565.hermes.repository.DigestRepository;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.alerts.AlertService;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Builds, persists and delivers the daily digest. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DigestService {

    private final MessageRepository messageRepository;
    private final DigestRepository digestRepository;
    private final AlertService alertService;
    private final NotificationService notificationService;
    private final HermesProperties properties;
    private final ObjectMapper objectMapper;
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
    @Transactional
    public void sendDailyDigest() {
        LocalDate today = LocalDate.now(clock);
        DigestDto digest = build(today);

        if (properties.getDigest().isSkipWhenEmpty() && digest.counts().total() == 0) {
            log.info("Nothing arrived today — skipping the digest");
            return;
        }

        boolean delivered =
                notificationService.pushDigest(
                        "Digest · %d high · %d normal"
                                .formatted(digest.counts().high(), digest.counts().normal()),
                        render(digest));

        persist(today, digest, delivered ? Instant.now(clock) : null);
        log.info(
                "Digest for {}: {} high / {} normal / {} noise (delivered={})",
                today,
                digest.counts().high(),
                digest.counts().normal(),
                digest.counts().noise(),
                delivered);
    }

    /** Builds today's digest live, so the widget always reflects the current state of the day. */
    @Transactional(readOnly = true)
    public DigestDto today() {
        return build(LocalDate.now(clock));
    }

    /**
     * Historical digests are read back from the stored record, not rebuilt: retention deletion and
     * later re-classification would otherwise silently rewrite the past.
     */
    @Transactional(readOnly = true)
    public DigestDto forDate(LocalDate date) {
        if (date.equals(LocalDate.now(clock))) {
            return today();
        }
        DigestEntity stored =
                digestRepository
                        .findByDate(date)
                        .orElseThrow(() -> new DigestNotFoundException(date));
        try {
            DigestDto content = objectMapper.readValue(stored.getContent(), DigestDto.class);
            return new DigestDto(
                    content.date(),
                    content.counts(),
                    content.high(),
                    content.normal(),
                    content.noise(),
                    content.alerts(),
                    content.unclassified(),
                    content.degraded(),
                    content.degradedReason(),
                    stored.getSentAt());
        } catch (Exception exception) {
            throw new DigestNotFoundException(date);
        }
    }

    DigestDto build(LocalDate date) {
        ZoneId zone = properties.getTimezone();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<MessageEntity> messages =
                messageRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to);

        List<MessageDto> high = byPriority(messages, Priority.HIGH);
        List<MessageDto> normal = byPriority(messages, Priority.NORMAL);
        List<MessageEntity> noise =
                messages.stream()
                        .filter(message -> message.getPriority() == Priority.NOISE)
                        .toList();

        int unclassified =
                (int)
                        messages.stream()
                                .filter(
                                        message ->
                                                message.getClassifiedBy() == ClassifiedBy.FALLBACK)
                                .count();

        List<AlertEventDto> alerts =
                alertService.between(from, to).stream().map(AlertEventDto::from).toList();

        return new DigestDto(
                date,
                new DigestDto.Counts(high.size(), normal.size(), noise.size()),
                high,
                normal,
                summariseNoise(noise),
                alerts,
                unclassified,
                unclassified > 0,
                unclassified > 0
                        ? "%d message(s) are unclassified — the classifier was unavailable"
                                .formatted(unclassified)
                        : null,
                null);
    }

    private static List<MessageDto> byPriority(List<MessageEntity> messages, Priority priority) {
        return messages.stream()
                .filter(message -> message.getPriority() == priority)
                .sorted(Comparator.comparing(MessageEntity::getReceivedAt).reversed())
                .map(MessageDto::from)
                .toList();
    }

    /**
     * Noise is bucketed by a coarse guess at what it is, so the count stays auditable — "23 noise"
     * is only trustworthy if you can see it was 18 newsletters and not 18 dropped invoices.
     */
    private static DigestDto.NoiseSummary summariseNoise(List<MessageEntity> noise) {
        Map<String, Integer> buckets = new java.util.LinkedHashMap<>();
        for (MessageEntity message : noise) {
            buckets.merge(categorise(message), 1, Integer::sum);
        }
        List<DigestDto.NoiseSummary.Category> categories =
                buckets.entrySet().stream()
                        .map(
                                entry ->
                                        new DigestDto.NoiseSummary.Category(
                                                entry.getKey(), entry.getValue()))
                        .sorted(
                                Comparator.comparingInt(DigestDto.NoiseSummary.Category::count)
                                        .reversed())
                        .toList();
        return new DigestDto.NoiseSummary(noise.size(), categories);
    }

    private static String categorise(MessageEntity message) {
        String sender = message.getSender().toLowerCase(Locale.ROOT);
        if (sender.contains("linkedin")
                || sender.contains("facebook")
                || sender.contains("instagram")
                || sender.contains("x.com")) {
            return "social";
        }
        String reason =
                message.getReason() == null ? "" : message.getReason().toLowerCase(Locale.ROOT);
        if (reason.contains("promotion") || reason.contains("marketing")) {
            return "promotions";
        }
        return "newsletters";
    }

    /** Plain-text rendering for the ntfy body. Deliberately terse — the app has the full view. */
    String render(DigestDto digest) {
        List<String> lines = new ArrayList<>();
        if (digest.degraded()) {
            lines.add("! " + digest.degradedReason());
            lines.add("");
        }
        if (!digest.high().isEmpty()) {
            lines.add("HIGH");
            digest.high()
                    .forEach(
                            message ->
                                    lines.add(
                                            "  %s — %s"
                                                    .formatted(
                                                            message.senderName(),
                                                            message.subject())));
            lines.add("");
        }
        if (!digest.normal().isEmpty()) {
            lines.add("NORMAL");
            digest.normal()
                    .forEach(
                            message ->
                                    lines.add(
                                            "  %s — %s"
                                                    .formatted(
                                                            message.senderName(),
                                                            Optional.ofNullable(message.summary())
                                                                    .orElse(message.subject()))));
            lines.add("");
        }
        if (properties.getDigest().isIncludeNoise() && digest.noise().count() > 0) {
            lines.add("NOISE — %d".formatted(digest.noise().count()));
        }
        List<AlertEventDto> firing =
                digest.alerts().stream().filter(alert -> alert.resolvedAt() == null).toList();
        if (!firing.isEmpty()) {
            lines.add("");
            lines.add("ALERTS — %d unresolved".formatted(firing.size()));
        }
        return String.join("\n", lines);
    }

    private void persist(LocalDate date, DigestDto digest, Instant sentAt) {
        DigestEntity entity =
                digestRepository
                        .findByDate(date)
                        .orElseGet(() -> DigestEntity.builder().date(date).build());
        try {
            entity.setContent(objectMapper.writeValueAsString(digest));
        } catch (Exception exception) {
            // Failing to persist the record must not swallow a digest that was already delivered.
            log.error("Could not serialise digest for {}: {}", date, exception.getMessage());
            return;
        }
        entity.setSentAt(sentAt);
        digestRepository.save(entity);
    }

    /** Unresolved alerts, for the alerts screen's "apps that paged" panel. */
    @Transactional(readOnly = true)
    public List<AlertEventEntity> unresolvedAlerts() {
        return alertService.unresolved();
    }
}
