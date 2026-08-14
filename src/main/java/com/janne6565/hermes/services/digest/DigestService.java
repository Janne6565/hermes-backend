package com.janne6565.hermes.services.digest;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.entity.DigestEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.AlertEventDto;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestRangeDto;
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
import java.time.temporal.ChronoUnit;
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
        DigestDto built = build(today);
        return digestRepository
                .findByDate(today)
                .map(stored -> withDelivery(built, storedNarrative(stored), stored.getSentAt()))
                .orElse(built);
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
        Span span = span(date, date);
        return new DigestDto(
                date,
                span.counts(),
                null,
                span.high(),
                span.normal(),
                span.noise(),
                span.alerts(),
                span.unclassified(),
                span.degraded(),
                span.degradedReason(),
                null);
    }

    /**
     * The same tally over several days, for the ad-hoc range report.
     *
     * <p>Read-only and unstored by design: this is a question someone asked, not a record of
     * something that was delivered. Nothing here touches {@code digestRepository}, so re-running it
     * can never rewrite what the evening send already committed to.
     */
    @Transactional(readOnly = true)
    public DigestRangeDto buildRange(LocalDate from, LocalDate to) {
        Span span = span(from, to);
        return new DigestRangeDto(
                from,
                to,
                (int) ChronoUnit.DAYS.between(from, to) + 1,
                span.counts(),
                null,
                span.high(),
                span.normal(),
                span.noise(),
                span.alerts(),
                span.unclassified(),
                span.degraded(),
                span.degradedReason());
    }

    /**
     * Everything a digest says about a stretch of time, from the first day's start to the last
     * day's end.
     *
     * <p>One query for the whole window rather than one per day — the range can be a quarter, and a
     * round trip per day would make the report's cost scale with the span the user happened to
     * pick.
     */
    private Span span(LocalDate from, LocalDate to) {
        ZoneId zone = properties.getTimezone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<MessageEntity> messages =
                messageRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(start, end);

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
                alertService.between(start, end).stream().map(AlertEventDto::from).toList();

        return new Span(
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
                        : null);
    }

    /** The shared shape of a day and a range, before either is dressed up as its own DTO. */
    private record Span(
            DigestDto.Counts counts,
            List<MessageDto> high,
            List<MessageDto> normal,
            DigestDto.NoiseSummary noise,
            List<AlertEventDto> alerts,
            int unclassified,
            boolean degraded,
            String degradedReason) {}

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
        return narrative == null ? digest : withDelivery(digest, narrative, digest.sentAt());
    }

    /**
     * Puts the stored delivery facts back onto a freshly built day.
     *
     * <p>Today's counts and lists are always rebuilt, but when it was sent and what was said about
     * it are recorded facts — rebuilding those would be inventing them.
     */
    private static DigestDto withDelivery(DigestDto digest, String narrative, Instant sentAt) {
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
                sentAt);
    }

    /**
     * Asks the sidecar to write the span up in prose.
     *
     * <p>Best effort exactly like {@link #narrate}: the range report is worth reading without a
     * paragraph, and a narrator that is down must not turn a working report into an error.
     */
    public DigestRangeDto narrateRange(DigestRangeDto range) {
        if (!properties.getDigest().isNarrative()) {
            return range;
        }
        try {
            String narrative = sidecarClient.summarise(summaryRequest(range)).orElse(null);
            return narrative == null ? range : withNarrative(range, narrative);
        } catch (Exception exception) {
            log.warn(
                    "Could not narrate the range {} to {}: {}",
                    range.from(),
                    range.to(),
                    exception.getMessage());
            return range;
        }
    }

    private static DigestRangeDto withNarrative(DigestRangeDto range, String narrative) {
        return new DigestRangeDto(
                range.from(),
                range.to(),
                range.days(),
                range.counts(),
                narrative,
                range.high(),
                range.normal(),
                range.noise(),
                range.alerts(),
                range.unclassified(),
                range.degraded(),
                range.degradedReason());
    }

    private SidecarClient.DigestSummaryRequest summaryRequest(DigestDto digest) {
        return new SidecarClient.DigestSummaryRequest(
                digest.date(),
                null,
                null,
                digest.counts(),
                digest.high().stream().map(message -> item(message, false)).toList(),
                digest.normal().stream().map(message -> item(message, false)).toList(),
                digest.noise().categories(),
                openAlertTitles(digest.alerts()),
                digest.unclassified());
    }

    /**
     * The same request for a span. The per-message date is only sent here: over several days "who
     * wrote when" is half of what the paragraph is for, while on a single day it would be the same
     * string on every line and pure prompt weight.
     */
    private SidecarClient.DigestSummaryRequest summaryRequest(DigestRangeDto range) {
        return new SidecarClient.DigestSummaryRequest(
                null,
                range.from(),
                range.to(),
                range.counts(),
                range.high().stream().map(message -> item(message, true)).toList(),
                range.normal().stream().map(message -> item(message, true)).toList(),
                range.noise().categories(),
                openAlertTitles(range.alerts()),
                range.unclassified());
    }

    private static List<String> openAlertTitles(List<AlertEventDto> alerts) {
        return alerts.stream()
                .filter(alert -> alert.resolvedAt() == null)
                .map(AlertEventDto::title)
                .toList();
    }

    private SidecarClient.DigestSummaryRequest.Item item(MessageDto message, boolean withDate) {
        return new SidecarClient.DigestSummaryRequest.Item(
                message.senderName(),
                message.subject(),
                message.summary(),
                withDate
                        ? LocalDate.ofInstant(message.receivedAt(), properties.getTimezone())
                        : null);
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
