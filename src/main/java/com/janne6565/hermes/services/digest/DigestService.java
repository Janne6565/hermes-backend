package com.janne6565.hermes.services.digest;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.entity.DigestEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestStatsDto;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.exception.DigestNotFoundException;
import com.janne6565.hermes.repository.DigestRepository;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.alerts.AlertService;
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
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Builds, persists and delivers the daily digest. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DigestService {

    /**
     * Ceiling on the pushed body. ntfy carries the digest as one message, and a body that outgrows
     * the server's limit is rejected or turned into an attachment — a text file is exactly the
     * thing this digest is not supposed to be.
     */
    private static final int MAX_PUSH_BODY_LENGTH = 3500;

    /**
     * Lines per section in the push. Beyond this the list stops being read and starts scrolling.
     */
    private static final int MAX_LISTED_PER_SECTION = 8;

    private final MessageRepository messageRepository;
    private final DigestRepository digestRepository;
    private final AlertService alertService;
    private final SidecarClient sidecarClient;
    private final HermesProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * The day as it stands, for {@link DigestSender} to narrate and deliver.
     *
     * <p>Separate from {@link #today()} because the sender is about to *write* the narrative — it
     * must see the day, not yesterday's paragraph about it.
     */
    @Transactional(readOnly = true)
    public DigestDto buildForDelivery(LocalDate date) {
        return build(date);
    }

    /**
     * Records what was delivered.
     *
     * <p>Its own short transaction, entered after the push has already happened. The alternative —
     * one transaction spanning the whole send — would hold a connection open across an LLM call and
     * an HTTP round trip, which is a lot of database to hold for work that never touches it.
     */
    @Transactional
    public void recordDelivery(LocalDate date, DigestDto digest, Instant sentAt) {
        persist(date, digest, sentAt);
    }

    /**
     * Builds today's digest live, so the widget always reflects the current state of the day.
     *
     * <p>The narrative is the one part that is *not* rebuilt: it is written once when the digest is
     * sent and then read back, so the app shows the same evening text that went to the phone rather
     * than a slightly different paragraph on every refresh.
     */
    @Transactional(readOnly = true)
    public DigestDto today() {
        LocalDate today = LocalDate.now(clock);
        return withNarrative(
                build(today),
                digestRepository.findByDate(today).map(this::storedNarrative).orElse(null));
    }

    private String storedNarrative(DigestEntity entity) {
        try {
            return objectMapper.readValue(entity.getContent(), DigestDto.class).narrative();
        } catch (Exception exception) {
            log.warn("Could not read the stored narrative for {}", entity.getDate());
            return null;
        }
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
                    content.narrative(),
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

    /**
     * Per-day counts for the last {@code days} days, oldest first.
     *
     * <p>One query over the whole window rather than one per day: the chart is a small, frequently
     * refreshed widget, and N round trips to render seven bars is the kind of thing that quietly
     * becomes the slowest part of the screen.
     */
    @Transactional(readOnly = true)
    public DigestStatsDto stats(int days) {
        ZoneId zone = properties.getTimezone();
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(days - 1L);

        Instant from = first.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, List<MessageEntity>> byDay =
                messageRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to).stream()
                        .collect(
                                Collectors.groupingBy(
                                        message ->
                                                LocalDate.ofInstant(
                                                        message.getReceivedAt(), zone)));

        Set<LocalDate> stored =
                digestRepository.findByDateBetween(first, today).stream()
                        .map(DigestEntity::getDate)
                        .collect(Collectors.toSet());

        List<DigestStatsDto.Day> result = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            List<MessageEntity> messages = byDay.getOrDefault(date, List.of());
            result.add(
                    new DigestStatsDto.Day(
                            date,
                            countOf(messages, Priority.HIGH),
                            countOf(messages, Priority.NORMAL),
                            countOf(messages, Priority.NOISE),
                            (int)
                                    messages.stream()
                                            .filter(message -> message.getNotifiedAt() != null)
                                            .count(),
                            stored.contains(date)));
        }
        return new DigestStatsDto(List.copyOf(result));
    }

    private static int countOf(List<MessageEntity> messages, Priority priority) {
        return (int) messages.stream().filter(message -> message.getPriority() == priority).count();
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
                null,
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

    /**
     * Asks the sidecar to write the day up in prose.
     *
     * <p>Best effort by construction: an unavailable narrator returns the digest unchanged, and
     * {@link #render} falls back to the bare list. The digest going out late-but-plain is always
     * better than the digest not going out.
     */
    DigestDto narrate(DigestDto digest) {
        if (!properties.getDigest().isNarrative()) {
            return digest;
        }
        try {
            return withNarrative(
                    digest, sidecarClient.summarise(summaryRequest(digest)).orElse(null));
        } catch (Exception exception) {
            log.warn(
                    "Could not narrate the digest for {}: {}",
                    digest.date(),
                    exception.getMessage());
            return digest;
        }
    }

    private static DigestDto withNarrative(DigestDto digest, String narrative) {
        if (narrative == null) {
            return digest;
        }
        return new DigestDto(
                digest.date(),
                digest.counts(),
                narrative,
                digest.high(),
                digest.normal(),
                digest.noise(),
                digest.alerts(),
                digest.unclassified(),
                digest.degraded(),
                digest.degradedReason(),
                digest.sentAt());
    }

    private static SidecarClient.DigestSummaryRequest summaryRequest(DigestDto digest) {
        return new SidecarClient.DigestSummaryRequest(
                digest.date(),
                digest.counts(),
                digest.high().stream().map(DigestService::item).toList(),
                digest.normal().stream().map(DigestService::item).toList(),
                digest.noise().categories(),
                digest.alerts().stream()
                        .filter(alert -> alert.resolvedAt() == null)
                        .map(AlertEventDto::title)
                        .toList(),
                digest.unclassified());
    }

    private static SidecarClient.DigestSummaryRequest.Item item(MessageDto message) {
        return new SidecarClient.DigestSummaryRequest.Item(
                message.senderName(), message.subject(), message.summary());
    }

    /**
     * Plain-text rendering for the ntfy body.
     *
     * <p>The narrative leads and the list is the appendix under it. That order is the point: a
     * notification is read on a lock screen in one glance, and a wall of "sender — subject" lines
     * is something the user has to work through rather than read. The list stays because the prose
     * is model output — it is the auditable version of the same day.
     */
    String render(DigestDto digest) {
        List<String> lines = new ArrayList<>();
        if (digest.degraded()) {
            lines.add("! " + digest.degradedReason());
            lines.add("");
        }
        if (digest.narrative() != null && !digest.narrative().isBlank()) {
            lines.add(digest.narrative());
            lines.add("");
        }
        appendSection(lines, "HIGH", digest.high(), MessageDto::subject);
        appendSection(
                lines,
                "NORMAL",
                digest.normal(),
                message ->
                        Optional.ofNullable(message.summary())
                                .filter(summary -> !summary.isBlank())
                                .orElse(message.subject()));
        if (properties.getDigest().isIncludeNoise() && digest.noise().count() > 0) {
            lines.add("NOISE — %d".formatted(digest.noise().count()));
        }
        List<AlertEventDto> firing =
                digest.alerts().stream().filter(alert -> alert.resolvedAt() == null).toList();
        if (!firing.isEmpty()) {
            lines.add("");
            lines.add("ALERTS — %d unresolved".formatted(firing.size()));
        }
        return clamp(String.join("\n", lines).strip());
    }

    private static void appendSection(
            List<String> lines,
            String heading,
            List<MessageDto> messages,
            java.util.function.Function<MessageDto, String> detail) {
        if (messages.isEmpty()) {
            return;
        }
        lines.add(heading);
        messages.stream()
                .limit(MAX_LISTED_PER_SECTION)
                .forEach(
                        message ->
                                lines.add(
                                        "  %s — %s"
                                                .formatted(
                                                        message.senderName(),
                                                        detail.apply(message))));
        int hidden = messages.size() - MAX_LISTED_PER_SECTION;
        if (hidden > 0) {
            lines.add("  … und %d weitere".formatted(hidden));
        }
        lines.add("");
    }

    /** Cuts at a line boundary, so a truncated push never ends halfway through a sender. */
    private static String clamp(String body) {
        if (body.length() <= MAX_PUSH_BODY_LENGTH) {
            return body;
        }
        String head = body.substring(0, MAX_PUSH_BODY_LENGTH);
        int lastBreak = head.lastIndexOf('\n');
        return (lastBreak > 0 ? head.substring(0, lastBreak) : head) + "\n…";
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
