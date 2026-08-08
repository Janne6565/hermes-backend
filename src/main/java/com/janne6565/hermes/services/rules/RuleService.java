package com.janne6565.hermes.services.rules;

import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.entity.RuleEntity;
import com.janne6565.hermes.model.action.CreateRuleRequest;
import com.janne6565.hermes.model.action.FeedbackRequest;
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

        RuleType type = request.applyToDomain() ? RuleType.DOMAIN : RuleType.SENDER;
        String pattern =
                request.applyToDomain()
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
