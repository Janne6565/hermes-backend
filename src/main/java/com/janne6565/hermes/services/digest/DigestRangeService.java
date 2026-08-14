package com.janne6565.hermes.services.digest;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.model.core.DigestRangeDto;
import com.janne6565.hermes.model.exception.InvalidDigestRangeException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the ad-hoc digest for a span the user picked.
 *
 * <p>Its own bean for the same reason {@link DigestSender} is one: the work is a short
 * transactional read followed by an LLM call that may take a minute and a half, and Spring's
 * transaction proxy only applies to calls arriving from outside the bean. Doing both inside {@link
 * DigestService} would have held a database connection open across the narrator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DigestRangeService {

    /**
     * Ceiling on the span, in days.
     *
     * <p>A quarter is more than anyone reads in one paragraph, and the bound is what stops a
     * mistyped year from pulling two years of mail through the narrator. The prompt is capped
     * independently in the sidecar, so this limit protects the query, not the model.
     */
    static final int MAX_RANGE_DAYS = 92;

    private final DigestService digestService;
    private final HermesProperties properties;
    private final Clock clock;

    /**
     * Builds and narrates the span. Never stored and never pushed — see {@link DigestRangeDto}.
     *
     * @throws InvalidDigestRangeException when the span is inverted, too long, or runs past today
     */
    public DigestRangeDto create(LocalDate from, LocalDate to) {
        validate(from, to);

        // Phase 1 — read, transactional, over before anything slow starts.
        DigestRangeDto range = digestService.buildRange(from, to);

        // Phase 2 — narrate. No transaction, no connection held: the sidecar never touches the
        // database and is allowed to be slow.
        DigestRangeDto narrated = digestService.narrateRange(range);

        log.debug(
                "Range digest {} to {}: {} high / {} normal / {} noise",
                from,
                to,
                narrated.counts().high(),
                narrated.counts().normal(),
                narrated.counts().noise());
        return narrated;
    }

    private void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw InvalidDigestRangeException.inverted(from, to);
        }
        LocalDate today = LocalDate.now(clock.withZone(properties.getTimezone()));
        if (to.isAfter(today)) {
            throw InvalidDigestRangeException.inTheFuture(to, today);
        }
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw InvalidDigestRangeException.tooLong(days, MAX_RANGE_DAYS);
        }
    }
}
