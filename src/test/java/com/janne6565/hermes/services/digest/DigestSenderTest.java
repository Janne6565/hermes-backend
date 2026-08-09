package com.janne6565.hermes.services.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The send is three phases with very different costs, and only two of them touch the database.
 * These tests pin that sequence, because the failure mode of getting it wrong is invisible: it
 * still works, it just holds a connection open across a ninety-second LLM call.
 */
class DigestSenderTest {

    private static final Instant NOW = Instant.parse("2026-08-09T16:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

    private final DigestService digestService = mock(DigestService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final HermesProperties properties = new HermesProperties();

    private final DigestSender sender =
            new DigestSender(
                    digestService,
                    notificationService,
                    properties,
                    Clock.fixed(NOW, ZoneId.of("Europe/Berlin")));

    @Test
    void readsThenNarratesThenPushesThenRecords() {
        DigestDto built = digest(3);
        DigestDto narrated = digest(3);
        when(digestService.buildForDelivery(TODAY)).thenReturn(built);
        when(digestService.narrate(built)).thenReturn(narrated);
        when(digestService.render(narrated)).thenReturn("body");
        when(notificationService.pushDigest(any(), eq("body"))).thenReturn(true);

        sender.sendDailyDigest();

        InOrder order = inOrder(digestService, notificationService);
        order.verify(digestService).buildForDelivery(TODAY);
        order.verify(digestService).narrate(built);
        order.verify(notificationService).pushDigest(any(), eq("body"));
        order.verify(digestService).recordDelivery(TODAY, narrated, NOW);
    }

    @Test
    void anUndeliveredDigestIsStillRecorded_justNotAsSent() {
        // The record is the audit trail. A day that failed to push must not read as a day with no
        // mail — it has to be visible as a digest that was built and never arrived.
        when(digestService.buildForDelivery(TODAY)).thenReturn(digest(1));
        when(digestService.narrate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.pushDigest(any(), any())).thenReturn(false);

        sender.sendDailyDigest();

        ArgumentCaptor<Instant> sentAt = ArgumentCaptor.forClass(Instant.class);
        verify(digestService).recordDelivery(eq(TODAY), any(), sentAt.capture());
        assertThat(sentAt.getValue()).isNull();
    }

    @Test
    void anEmptyDayCostsNoLlmCallWhenSkippingIsOn() {
        properties.getDigest().setSkipWhenEmpty(true);
        when(digestService.buildForDelivery(TODAY)).thenReturn(digest(0));

        sender.sendDailyDigest();

        verify(digestService, never()).narrate(any());
        verify(notificationService, never()).pushDigest(any(), any());
        verify(digestService, never()).recordDelivery(any(), any(), any());
    }

    @Test
    void pressingTheButtonSendsEvenOnADayThatWouldHaveBeenSkipped() {
        // skipWhenEmpty exists so a quiet day does not buzz the phone unasked. Someone pressing the
        // button has asked, so the setting must not silently swallow the request.
        properties.getDigest().setSkipWhenEmpty(true);
        DigestDto empty = digest(0);
        when(digestService.buildForDelivery(TODAY)).thenReturn(empty);
        when(digestService.narrate(empty)).thenReturn(empty);
        when(digestService.today()).thenReturn(empty);

        assertThat(sender.sendNow()).isSameAs(empty);

        verify(digestService).narrate(empty);
        verify(notificationService).pushDigest(any(), any());
    }

    private static DigestDto digest(int high) {
        return new DigestDto(
                TODAY,
                new DigestDto.Counts(high, 0, 0),
                null,
                List.of(),
                List.of(),
                new DigestDto.NoiseSummary(0, List.of()),
                List.of(),
                0,
                false,
                null,
                null);
    }
}
