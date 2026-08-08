package com.janne6565.hermes.services.classification;

import com.janne6565.hermes.client.GmailClient;
import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.notification.NotificationService;
import com.janne6565.hermes.services.rules.RuleEngine;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one message through the pipeline: hard rules, then the classifier, then the fallback.
 *
 * <p>The fallback tier is what keeps a dead sidecar from being a silent failure. An unclassifiable
 * message is stored as {@code normal} and flagged {@code fallback}, which does two things: it never
 * pushes (so a broken classifier cannot spam the phone), and it shows up as a warning line in the
 * digest and a re-classification candidate for the nightly job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassificationService {

    private final RuleEngine ruleEngine;
    private final SidecarClient sidecarClient;
    private final MessageRepository messageRepository;
    private final NotificationService notificationService;

    /**
     * Classifies and persists a freshly fetched message, pushing if it earns an interrupt.
     *
     * @return the stored row, or empty if we had already seen this Gmail id.
     */
    @Transactional
    public Optional<MessageEntity> ingest(GmailClient.FetchedMessage fetched) {
        if (messageRepository.existsByGmailId(fetched.gmailId())) {
            return Optional.empty();
        }

        Verdict verdict = classify(fetched);
        MessageEntity message =
                MessageEntity.builder()
                        .gmailId(fetched.gmailId())
                        .sender(fetched.sender())
                        .subject(fetched.subject())
                        .snippet(fetched.snippet())
                        .receivedAt(fetched.receivedAt())
                        .priority(verdict.priority())
                        .reason(verdict.reason())
                        .summary(verdict.summary())
                        .classifiedBy(verdict.classifiedBy())
                        .build();

        messageRepository.save(message);

        if (verdict.priority() == Priority.HIGH) {
            notificationService.pushHighPriorityMail(message);
        }
        return Optional.of(message);
    }

    /**
     * Nightly repair pass: re-runs the classifier over everything the sidecar missed.
     *
     * <p>These deliberately do <em>not</em> push even when they come back {@code high} — the moment
     * to interrupt has passed, and a 3am burst of notifications for yesterday's mail is exactly the
     * behaviour this service exists to eliminate. They surface in the next digest instead.
     */
    @Transactional
    public int reclassifyFallbacks() {
        if (!sidecarClient.isHealthy()) {
            log.info("Sidecar still unhealthy; skipping fallback re-classification");
            return 0;
        }

        List<MessageEntity> pending =
                messageRepository.findByClassifiedByOrderByReceivedAtAsc(ClassifiedBy.FALLBACK);
        int repaired = 0;

        for (MessageEntity message : pending) {
            Optional<SidecarClient.ClassificationResponse> response =
                    sidecarClient.classify(
                            new SidecarClient.ClassificationRequest(
                                    message.getSender(),
                                    message.getSubject(),
                                    message.getSnippet()));
            if (response.isEmpty()) {
                log.warn("Sidecar went unhealthy mid-retry; stopping after {} messages", repaired);
                break;
            }
            SidecarClient.ClassificationResponse verdict = response.get();
            message.setPriority(verdict.priority());
            message.setReason(verdict.reason());
            message.setSummary(verdict.summary());
            message.setClassifiedBy(ClassifiedBy.LLM);
            repaired++;
        }

        if (repaired > 0) {
            log.info("Re-classified {} fallback messages", repaired);
        }
        return repaired;
    }

    private Verdict classify(GmailClient.FetchedMessage fetched) {
        Optional<RuleEngine.Match> ruleMatch = ruleEngine.evaluate(fetched);
        if (ruleMatch.isPresent()) {
            RuleEngine.Match match = ruleMatch.get();
            return new Verdict(
                    match.priority(), match.reason(), truncateSubject(fetched), ClassifiedBy.RULE);
        }

        Optional<SidecarClient.ClassificationResponse> llm =
                sidecarClient.classify(
                        new SidecarClient.ClassificationRequest(
                                fetched.sender(), fetched.subject(), fetched.snippet()));

        return llm.map(
                        response ->
                                new Verdict(
                                        response.priority(),
                                        response.reason(),
                                        response.summary(),
                                        ClassifiedBy.LLM))
                .orElseGet(
                        () ->
                                new Verdict(
                                        Priority.NORMAL,
                                        "classifier unavailable",
                                        truncateSubject(fetched),
                                        ClassifiedBy.FALLBACK));
    }

    /** Rule and fallback paths have no LLM summary; the subject is the honest stand-in. */
    private static String truncateSubject(GmailClient.FetchedMessage fetched) {
        String subject = fetched.subject();
        return subject.length() <= 120 ? subject : subject.substring(0, 117) + "...";
    }

    private record Verdict(
            Priority priority, String reason, String summary, ClassifiedBy classifiedBy) {}
}
