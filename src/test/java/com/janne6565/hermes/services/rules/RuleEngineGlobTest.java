package com.janne6565.hermes.services.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The glob translation is the one place user input reaches the regex engine, so it gets its own
 * test — a pattern that leaked regex metacharacters would match far more mail than intended.
 */
class RuleEngineGlobTest {

    @Test
    void matchesLiteralPatterns() {
        assertThat(RuleEngine.globMatches("no-reply@meetup.com", "no-reply@meetup.com")).isTrue();
        assertThat(RuleEngine.globMatches("no-reply@meetup.com", "hello@meetup.com")).isFalse();
    }

    @Test
    void matchesWildcards() {
        assertThat(RuleEngine.globMatches("*.uni-potsdam.de", "mail.uni-potsdam.de")).isTrue();
        assertThat(RuleEngine.globMatches("*.uni-potsdam.de", "uni-potsdam.de")).isFalse();
        assertThat(RuleEngine.globMatches("*@github.com", "security@github.com")).isTrue();
    }

    @Test
    void isCaseInsensitive() {
        assertThat(RuleEngine.globMatches("*@GitHub.com", "security@github.com")).isTrue();
    }

    @Test
    void treatsRegexMetacharactersAsLiterals() {
        // A '.' must not act as "any character" — otherwise *.uni-potsdam.de would also match
        // a lookalike domain like uniXpotsdam.de.
        assertThat(RuleEngine.globMatches("*.uni-potsdam.de", "mail.uniXpotsdam.de")).isFalse();
        assertThat(RuleEngine.globMatches("a+b@example.com", "a+b@example.com")).isTrue();
        assertThat(RuleEngine.globMatches("a+b@example.com", "aab@example.com")).isFalse();
    }

    @Test
    void extractsAddressAndDomainFromAHeader() {
        String from = "Dr. Anna Weber <weber@uni-potsdam.de>";
        assertThat(RuleEngine.emailAddress(from)).isEqualTo("weber@uni-potsdam.de");
        assertThat(RuleEngine.domainOf(from)).isEqualTo("uni-potsdam.de");
    }
}
