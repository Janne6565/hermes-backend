package com.janne6565.hermes.services.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers what the phone actually shows. The narrative is model output, so the tests are about the
 * shape of the push and the fallback — never about the wording.
 */
class DigestRenderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 9);

    /** Mirrors DigestService's own ceiling — the assertion is about that contract, not a number. */
    private static final int MAX_PUSH_BODY_LENGTH = 3500;

    private final SidecarClient sidecarClient = mock(SidecarClient.class);
    private final HermesProperties properties = new HermesProperties();

    private final DigestService service =
            new DigestService(null, null, null, null, sidecarClient, properties, null, null);

    @Test
    void narrativeLeadsAndTheListFollows() {
        String body =
                service.render(
                        digest(
                                "Anna fragt nach dem Freitagstermin.",
                                List.of(message("Anna")),
                                List.of()));

        assertThat(body)
                .startsWith("Anna fragt nach dem Freitagstermin.")
                .contains("HIGH")
                .contains("Anna — Betreff");
        assertThat(body.indexOf("Anna fragt")).isLessThan(body.indexOf("HIGH"));
    }

    @Test
    void withoutANarrativeItRendersTheListItAlwaysDid() {
        String body = service.render(digest(null, List.of(message("Anna")), List.of()));

        assertThat(body).startsWith("HIGH").contains("Anna — Betreff");
    }

    @Test
    void longSectionsAreCappedRatherThanScrolled() {
        List<MessageDto> many = IntStream.range(0, 20).mapToObj(i -> message("S" + i)).toList();

        String body = service.render(digest("Viel los.", List.of(), many));

        assertThat(body).contains("… und 12 weitere");
        assertThat(body.lines().filter(line -> line.startsWith("  S")).count()).isEqualTo(8);
    }

    @Test
    void theBodyStaysInsideWhatNtfyCarriesAsAMessage() {
        // Above the limit ntfy stops rendering a notification and starts producing an attachment,
        // which is precisely the plain text file this digest exists to replace. The per-section cap
        // alone does not get us there — sixteen lines of a 400-character subject does.
        List<MessageDto> wordy =
                IntStream.range(0, 20)
                        .mapToObj(index -> message("S" + index, "B".repeat(400)))
                        .toList();

        String body = service.render(digest("x".repeat(700), wordy, wordy));

        assertThat(body.length()).isLessThanOrEqualTo(MAX_PUSH_BODY_LENGTH + 2);
        assertThat(body).endsWith("…");
    }

    @Test
    void anOrdinaryDayNeverNeedsTruncatingAtAll() {
        List<MessageDto> many =
                IntStream.range(0, 60).mapToObj(index -> message("S" + index)).toList();

        assertThat(service.render(digest("x".repeat(700), many, many)))
                .doesNotEndWith("…")
                .hasSizeLessThan(MAX_PUSH_BODY_LENGTH);
    }

    @Test
    void anUnavailableNarratorLeavesTheDigestIntactRatherThanFailingTheSend() {
        when(sidecarClient.summarise(any())).thenReturn(Optional.empty());

        DigestDto result = service.narrate(digest(null, List.of(message("Anna")), List.of()));

        assertThat(result.narrative()).isNull();
        assertThat(result.high()).hasSize(1);
    }

    @Test
    void narrationIsSkippedEntirelyWhenTurnedOff() {
        properties.getDigest().setNarrative(false);

        assertThat(service.narrate(digest(null, List.of(), List.of())).narrative()).isNull();
        org.mockito.Mockito.verifyNoInteractions(sidecarClient);
    }

    @Test
    void theNarratorSeesOnlyTheThreeFieldsTheClassifierWasAllowed() {
        when(sidecarClient.summarise(any())).thenReturn(Optional.of("Ruhiger Tag."));

        service.narrate(digest(null, List.of(message("Anna")), List.of()));

        var captor = org.mockito.ArgumentCaptor.forClass(SidecarClient.DigestSummaryRequest.class);
        org.mockito.Mockito.verify(sidecarClient).summarise(captor.capture());

        SidecarClient.DigestSummaryRequest.Item item = captor.getValue().high().getFirst();
        assertThat(item.sender()).isEqualTo("Anna");
        assertThat(item.subject()).isEqualTo("Betreff");
        assertThat(item.summary()).isEqualTo("Zusammenfassung");
    }

    private static DigestDto digest(
            String narrative, List<MessageDto> high, List<MessageDto> normal) {
        return new DigestDto(
                DATE,
                new DigestDto.Counts(high.size(), normal.size(), 0),
                narrative,
                high,
                normal,
                new DigestDto.NoiseSummary(0, List.of()),
                List.of(),
                0,
                false,
                null,
                null);
    }

    private static MessageDto message(String sender) {
        return message(sender, "Betreff");
    }

    private static MessageDto message(String sender, String subject) {
        return new MessageDto(
                UUID.randomUUID(),
                "gmail-id",
                sender + " <" + sender + "@example.com>",
                sender,
                subject,
                "Auszug",
                Instant.parse("2026-08-09T10:00:00Z"),
                Priority.HIGH,
                "Zusammenfassung",
                "Grund",
                ClassifiedBy.LLM,
                null,
                null,
                null,
                null,
                false,
                "https://mail.google.com/",
                null);
    }
}
