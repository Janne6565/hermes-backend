package com.janne6565.hermes.services.rules;

import com.janne6565.hermes.client.GmailClient;
import com.janne6565.hermes.entity.RuleEntity;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.repository.RuleRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage one of the pipeline: deterministic, user-editable rules evaluated before any LLM call.
 *
 * <p>Ordering is deliberate and is the whole safety story of the service. An allowlist hit must
 * beat a {@code List-Unsubscribe} block, or a university newsletter-style mail with a real deadline
 * would be silenced. So: {@code high} rules win, then {@code normal}, then {@code noise}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleEngine {

    private final RuleRepository ruleRepository;

    /**
     * @return the matched rule's priority, or empty when nothing matched and the message should go
     *     to the classifier.
     */
    @Transactional
    public Optional<Match> evaluate(GmailClient.FetchedMessage message) {
        List<RuleEntity> rules = ruleRepository.findByEnabledTrue();

        for (Priority tier : List.of(Priority.HIGH, Priority.NORMAL, Priority.NOISE)) {
            Optional<RuleEntity> hit =
                    rules.stream()
                            .filter(rule -> rule.getPriority() == tier)
                            .filter(rule -> matches(rule, message))
                            .findFirst();
            if (hit.isPresent()) {
                RuleEntity rule = hit.get();
                rule.setHits(rule.getHits() + 1);
                log.debug(
                        "Rule {} ({} {}) matched message {}",
                        rule.getId(),
                        rule.getType().wire(),
                        rule.getPattern(),
                        message.gmailId());
                return Optional.of(
                        new Match(
                                rule.getPriority(),
                                "matched %s rule %s"
                                        .formatted(rule.getType().wire(), rule.getPattern())));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(RuleEntity rule, GmailClient.FetchedMessage message) {
        return switch (rule.getType()) {
            case SENDER -> globMatches(rule.getPattern(), emailAddress(message.sender()));
            case DOMAIN -> globMatches(rule.getPattern(), domainOf(message.sender()));
            case HEADER -> headerMatches(rule.getPattern(), message);
        };
    }

    /**
     * A header rule is either a bare name ({@code List-Unsubscribe} — present at all) or {@code
     * Name: value} where the value is glob-matched.
     */
    private static boolean headerMatches(String pattern, GmailClient.FetchedMessage message) {
        int separator = pattern.indexOf(':');
        if (separator < 0) {
            return message.headers().containsKey(pattern.trim().toLowerCase(Locale.ROOT));
        }
        String name = pattern.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String expected = pattern.substring(separator + 1).trim();
        String actual = message.headers().get(name);
        return actual != null && globMatches(expected, actual);
    }

    /** {@code Dr. Anna Weber <weber@uni-potsdam.de>} → {@code weber@uni-potsdam.de}. */
    public static String emailAddress(String from) {
        int open = from.indexOf('<');
        int close = from.indexOf('>');
        String address = (open >= 0 && close > open) ? from.substring(open + 1, close) : from;
        return address.trim().toLowerCase(Locale.ROOT);
    }

    public static String domainOf(String from) {
        String address = emailAddress(from);
        int at = address.lastIndexOf('@');
        return at < 0 ? address : address.substring(at + 1);
    }

    /**
     * Glob matching with {@code *} only. Patterns are user-authored and stored in Postgres, so the
     * pattern is quoted and only the wildcard is translated — a stray {@code .} or {@code +} in a
     * domain must stay literal, and no user input reaches the regex engine unescaped.
     */
    static boolean globMatches(String glob, String value) {
        String regex =
                java.util.Arrays.stream(glob.trim().toLowerCase(Locale.ROOT).split("\\*", -1))
                        .map(Pattern::quote)
                        .reduce((left, right) -> left + ".*" + right)
                        .orElse("");
        return Pattern.compile("^" + regex + "$").matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * @param reason human-readable, shown as the reason line wherever the message is rendered.
     */
    public record Match(Priority priority, String reason) {}
}
