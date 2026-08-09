package com.janne6565.hermes.services.rules;

import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.entity.RuleEntity;
import com.janne6565.hermes.model.action.CreateRuleRequest;
import com.janne6565.hermes.model.action.FeedbackRequest;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleDryRunDto;
import com.janne6565.hermes.model.core.RuleDto;
import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.model.exception.DuplicateRuleException;
import com.janne6565.hermes.model.exception.MessageNotFoundException;
import com.janne6565.hermes.model.exception.RuleNotFoundException;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.repository.RuleRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD over the rule table, plus the one-tap feedback path that writes rules for the user. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final RuleRepository ruleRepository;
    private final MessageRepository messageRepository;

    @Transactional(readOnly = true)
    public List<RuleDto> list() {
        return ruleRepository.findAllByOrderByCreatedAtDesc().stream().map(RuleDto::from).toList();
    }

    @Transactional
    public RuleDto create(CreateRuleRequest request) {
        return RuleDto.from(
                upsert(
                        request.type(),
                        request.pattern(),
                        request.priority(),
                        RuleSource.MANUAL,
                        true));
    }

    /**
     * Evaluates a candidate rule against recent stored mail.
     *
     * <p>The point is to make the consequence visible before the rule exists: "would have matched
     * 47 of the last 500, none of them high" is the difference between a safe noise rule and one
     * that would have swallowed a supervisor's deadline.
     */
    @Transactional(readOnly = true)
    public RuleDryRunDto dryRun(RuleType type, String pattern, int sampleSize) {
        if (pattern == null || pattern.isBlank()) {
            return RuleDryRunDto.unsupported("no pattern");
        }
        if (type == RuleType.HEADER) {
            // Headers are dropped after classification — data minimisation, and there is nothing
            // left to match against. A zero here would read as "this would never match".
            return RuleDryRunDto.unsupported("headers are not retained after classification");
        }

        List<MessageEntity> sample =
                messageRepository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, sampleSize));

        List<MessageEntity> matches =
                sample.stream()
                        .filter(
                                message ->
                                        RuleEngine.globMatches(
                                                pattern,
                                                type == RuleType.DOMAIN
                                                        ? RuleEngine.domainOf(message.getSender())
                                                        : RuleEngine.emailAddress(
                                                                message.getSender())))
                        .toList();

        int matchedHigh =
                (int)
                        matches.stream()
                                .filter(message -> message.getPriority() == Priority.HIGH)
                                .count();

        return new RuleDryRunDto(true, null, sample.size(), matches.size(), matchedHigh);
    }

    /** The most recent rules the user's own corrections produced. */
    @Transactional(readOnly = true)
    public List<RuleDto> recentFeedback(int limit) {
        return ruleRepository.findBySourceOrderByCreatedAtDesc(RuleSource.FEEDBACK).stream()
                .limit(limit)
                .map(RuleDto::from)
                .toList();
    }

    @Transactional
    public void delete(UUID ruleId) {
        RuleEntity rule =
                ruleRepository
                        .findById(ruleId)
                        .orElseThrow(() -> new RuleNotFoundException(ruleId));
        ruleRepository.delete(rule);
    }

    @Transactional
    public RuleDto setEnabled(UUID ruleId, boolean enabled) {
        RuleEntity rule =
                ruleRepository
                        .findById(ruleId)
                        .orElseThrow(() -> new RuleNotFoundException(ruleId));
        rule.setEnabled(enabled);
        return RuleDto.from(rule);
    }

    /**
     * Turns "this shouldn't have pinged me" into a rule.
     *
     * <p>The corrected priority is also written back onto the offending message, so the digest for
     * that day reflects the user's judgement rather than the classifier's.
     */
    @Transactional
    public RuleDto applyFeedback(FeedbackRequest request) {
        MessageEntity message =
                messageRepository
                        .findById(request.messageId())
                        .orElseThrow(() -> new MessageNotFoundException(request.messageId()));

        RuleType type = request.shouldApplyToDomain() ? RuleType.DOMAIN : RuleType.SENDER;
        String pattern =
                request.shouldApplyToDomain()
                        ? "*." + RuleEngine.domainOf(message.getSender())
                        : RuleEngine.emailAddress(message.getSender());

        message.setPriority(request.shouldHaveBeen());
        message.setReason("corrected by feedback");

        log.info(
                "Feedback: {} rule '{}' -> {}",
                type.wire(),
                pattern,
                request.shouldHaveBeen().wire());
        return RuleDto.from(
                upsert(type, pattern, request.shouldHaveBeen(), RuleSource.FEEDBACK, false));
    }

    /**
     * @param failOnDuplicate manual creation surfaces a conflict so the user notices; feedback taps
     *     silently update the existing rule instead of erroring on a second tap.
     */
    private RuleEntity upsert(
            RuleType type,
            String pattern,
            com.janne6565.hermes.model.core.Priority priority,
            RuleSource source,
            boolean failOnDuplicate) {
        return ruleRepository
                .findByTypeAndPattern(type, pattern)
                .map(
                        existing -> {
                            if (failOnDuplicate) {
                                throw new DuplicateRuleException(type, pattern);
                            }
                            existing.setPriority(priority);
                            existing.setEnabled(true);
                            return existing;
                        })
                .orElseGet(
                        () ->
                                ruleRepository.save(
                                        RuleEntity.builder()
                                                .type(type)
                                                .pattern(pattern)
                                                .priority(priority)
                                                .source(source)
                                                .enabled(true)
                                                .build()));
    }
}
