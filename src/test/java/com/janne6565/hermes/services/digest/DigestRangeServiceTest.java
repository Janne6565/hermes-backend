package com.janne6565.hermes.services.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.DigestDto;
import com.janne6565.hermes.model.core.DigestRangeDto;
import com.janne6565.hermes.model.exception.InvalidDigestRangeException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/**
 * The range report is the one digest that is built on demand and never delivered. These tests pin
 * the two properties that make it safe to run repeatedly: it validates the span before touching the
 * database, and it never writes anything.
 */
class DigestRangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    private static final LocalDate WEEK_AGO = LocalDate.of(2026, 8, 7);

    private final DigestService digestService = mock(DigestService.class);
    private final HermesProperties properties = new HermesProperties();

    private final DigestRangeService service =
            new DigestRangeService(
                    digestService, properties, Clock.fixed(NOW, ZoneId.of("Europe/Berlin")));

    @Test
    void buildsThenNarrates() {
        DigestRangeDto built = range(2);
        DigestRangeDto narrated = range(2);
        when(digestService.buildRange(WEEK_AGO, TODAY)).thenReturn(built);
        when(digestService.narrateRange(built)).thenReturn(narrated);

        assertThat(service.create(WEEK_AGO, TODAY)).isSameAs(narrated);

        // The read has to be over before the narrator starts — the whole reason this is its own
        // bean is that a transaction spanning both would hold a connection across the LLM call.
        InOrder order = inOrder(digestService);
        order.verify(digestService).buildRange(WEEK_AGO, TODAY);
        order.verify(digestService).narrateRange(built);
    }

    @Test
    void neverRecordsOrDelivers() {
        when(digestService.buildRange(WEEK_AGO, TODAY)).thenReturn(range(1));
        when(digestService.narrateRange(any())).thenReturn(range(1));

        service.create(WEEK_AGO, TODAY);

        // An ad-hoc report that wrote to the digest table would silently rewrite what was actually
        // delivered that evening, which is the one thing the stored digest is for.
        verify(digestService, never()).recordDelivery(any(), any(), any());
    }

    @Test
    void rejectsAnInvertedSpanBeforeQuerying() {
        assertThatThrownBy(() -> service.create(TODAY, WEEK_AGO))
                .isInstanceOf(InvalidDigestRangeException.class)
                .satisfies(
                        thrown ->
                                assertThat(((InvalidDigestRangeException) thrown).getStatus())
                                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(digestService);
    }

    @Test
    void rejectsASpanLongerThanTheCeiling() {
        LocalDate tooEarly = TODAY.minusDays(DigestRangeService.MAX_RANGE_DAYS);

        assertThatThrownBy(() -> service.create(tooEarly, TODAY))
                .isInstanceOf(InvalidDigestRangeException.class)
                .hasMessageContaining(String.valueOf(DigestRangeService.MAX_RANGE_DAYS));

        // One day shorter is exactly the ceiling, and must be allowed.
        LocalDate justInside = TODAY.minusDays(DigestRangeService.MAX_RANGE_DAYS - 1L);
        when(digestService.buildRange(justInside, TODAY)).thenReturn(range(0));
        when(digestService.narrateRange(any())).thenReturn(range(0));

        assertThat(service.create(justInside, TODAY)).isNotNull();
    }

    @Test
    void rejectsASpanThatRunsPastToday() {
        assertThatThrownBy(() -> service.create(WEEK_AGO, TODAY.plusDays(1)))
                .isInstanceOf(InvalidDigestRangeException.class)
                .hasMessageContaining("after today");

        verifyNoInteractions(digestService);
    }

    @Test
    void todayItselfIsInsideTheSpan() {
        when(digestService.buildRange(TODAY, TODAY)).thenReturn(range(0));
        when(digestService.narrateRange(any())).thenReturn(range(0));

        assertThat(service.create(TODAY, TODAY)).isNotNull();
    }

    private static DigestRangeDto range(int high) {
        return new DigestRangeDto(
                WEEK_AGO,
                TODAY,
                8,
                new DigestDto.Counts(high, 0, 0),
                null,
                List.of(),
                List.of(),
                new DigestDto.NoiseSummary(0, List.of()),
                List.of(),
                0,
                false,
                null);
    }
}
