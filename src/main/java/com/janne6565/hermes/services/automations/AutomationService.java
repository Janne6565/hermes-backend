package com.janne6565.hermes.services.automations;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.client.WebhookClient;
import com.janne6565.hermes.entity.AutomationEntity;
import com.janne6565.hermes.entity.AutomationRunEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.action.CreateAutomationRequest;
import com.janne6565.hermes.model.action.UpdateAutomationRequest;
import com.janne6565.hermes.model.core.ActionOutcome;
import com.janne6565.hermes.model.core.AutomationAlert;
import com.janne6565.hermes.model.core.AutomationDto;
import com.janne6565.hermes.model.core.AutomationOverviewDto;
import com.janne6565.hermes.model.core.AutomationRunDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.exception.AutomationLimitException;
import com.janne6565.hermes.model.exception.AutomationNotFoundException;
import com.janne6565.hermes.model.exception.AutomationWithoutActionException;
import com.janne6565.hermes.model.exception.DuplicateAutomationException;
import com.janne6565.hermes.repository.AutomationRepository;
import com.janne6565.hermes.repository.AutomationRunRepository;
import com.janne6565.hermes.services.notification.NotificationService;
import com.janne6565.hermes.services.notification.NotificationService.PushOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Natural-language triggers and the actions they run.
 *
 * <p>Detection is not done here. The enabled triggers ride along on the classifier call that
 * already happens for every mail, and the sidecar answers with the ids that matched — so this
 * service only hands out the vocabulary and carries out the verdict.
 *
 * <p>An automation never touches priority. Its actions sit on top of whatever the triage decided,
 * which means a noise-rated AWS pricing mail can still ping the phone if the user asked for that,
 * without teaching the classifier anything about what "high" means.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationService {

    /**
     * Mirrors {@code MAX_AUTOMATIONS} in the sidecar, which silently drops anything past it. Every
     * enabled trigger is prompt text on every classification, so this is also a cost bound.
     */
    public static final int MAX_ENABLED = 25;

    private static final int RECENT_RUNS = 30;

    private final AutomationRepository automationRepository;
    private final AutomationRunRepository runRepository;
    private final NotificationService notificationService;
    private final WebhookClient webhookClient;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AutomationOverviewDto overview() {
        return new AutomationOverviewDto(
                automationRepository.findAllByOrderByCreatedAtAsc().stream()
                        .map(AutomationDto::from)
                        .toList(),
                runRepository.findRecent(PageRequest.of(0, RECENT_RUNS)).stream()
                        .map(AutomationRunDto::from)
                        .toList(),
                MAX_ENABLED);
    }

    /** What the classifier is asked to match against — enabled automations only. */
    @Transactional(readOnly = true)
    public List<SidecarClient.AutomationTrigger> triggers() {
        return automationRepository.findByEnabledTrueOrderByCreatedAtAsc().stream()
                .map(a -> new SidecarClient.AutomationTrigger(a.getId().toString(), a.getTrigger()))
                .toList();
    }

    @Transactional
    public AutomationDto create(CreateAutomationRequest request) {
        String name = request.name().trim();
        ensureUniqueName(name, null);
        String webhook = blankToNull(request.webhookUrl());
        ensureHasAction(request.alert(), webhook);
        if (automationRepository.countByEnabledTrue() >= MAX_ENABLED) {
            throw new AutomationLimitException(MAX_ENABLED);
        }

        AutomationEntity created =
                automationRepository.save(
                        AutomationEntity.builder()
                                .name(name)
                                .trigger(request.trigger().trim())
                                .alert(request.alert())
                                .webhookUrl(webhook)
                                .createdAt(Instant.now(clock))
                                .build());
        log.info("Created automation '{}'", name);
        return AutomationDto.from(created);
    }

    @Transactional
    public AutomationDto update(UUID id, UpdateAutomationRequest request) {
        AutomationEntity automation = find(id);

        if (request.name() != null) {
            String name = request.name().trim();
            ensureUniqueName(name, id);
            automation.setName(name);
        }
        if (request.trigger() != null) {
            automation.setTrigger(request.trigger().trim());
        }
        if (request.alert() != null) {
            automation.setAlert(request.alert());
        }
        if (request.webhookUrl() != null) {
            automation.setWebhookUrl(blankToNull(request.webhookUrl()));
        }
        ensureHasAction(automation.getAlert(), automation.getWebhookUrl());

        if (request.enabled() != null && request.enabled() != automation.isEnabled()) {
            if (request.enabled() && automationRepository.countByEnabledTrue() >= MAX_ENABLED) {
                throw new AutomationLimitException(MAX_ENABLED);
            }
            automation.setEnabled(request.enabled());
        }
        return AutomationDto.from(automation);
    }

    @Transactional
    public void delete(UUID id) {
        AutomationEntity automation = find(id);
        automationRepository.delete(automation);
        log.info("Deleted automation '{}'", automation.getName());
    }

    /**
     * Runs every action of one automation against a placeholder, so a webhook can be wired up
     * before a real mail ever matches. Bypasses shadow mode and quiet hours on purpose, and does
     * not count towards {@code fireCount}.
     */
    @Transactional
    public AutomationRunDto test(UUID id) {
        AutomationEntity automation = find(id);
        return AutomationRunDto.from(execute(automation, null, Mode.TEST));
    }

    /**
     * Carries out the classifier's verdict for one stored message.
     *
     * <p>Ids the classifier returned that no longer name an enabled automation are ignored: the
     * user may have deleted or paused one while the turn was in flight.
     *
     * <p>Deliberately not {@code @Transactional}, and never throws. It runs inside the caller's
     * ingest transaction, and an exception crossing a transactional proxy marks that transaction
     * rollback-only even when caught — one broken automation would then un-store the message, and
     * the poll loop would fetch and classify it again on every tick.
     *
     * @param late the match came from the nightly re-classification of a message the sidecar
     *     missed. The webhook still runs — a receiver that files invoices wants the invoice either
     *     way — but the push is held back, for the same reason the nightly job never pushes: a 3am
     *     burst about yesterday's mail is the behaviour this service exists to prevent.
     */
    public void fire(MessageEntity message, Collection<String> matchedIds, boolean late) {
        if (matchedIds == null || matchedIds.isEmpty()) {
            return;
        }
        for (String raw : new LinkedHashSet<>(matchedIds)) {
            try {
                parse(raw)
                        .flatMap(automationRepository::findById)
                        .filter(AutomationEntity::isEnabled)
                        .ifPresent(
                                automation -> {
                                    execute(automation, message, late ? Mode.LATE : Mode.LIVE);
                                    automation.setFireCount(automation.getFireCount() + 1);
                                    automation.setLastFiredAt(Instant.now(clock));
                                });
            } catch (RuntimeException exception) {
                log.error("Automation {} failed for message {}", raw, message.getId(), exception);
            }
        }
    }

    private AutomationRunEntity execute(
            AutomationEntity automation, MessageEntity message, Mode mode) {
        List<String> details = new ArrayList<>();

        ActionOutcome alert = ActionOutcome.NONE;
        if (automation.getAlert() != AutomationAlert.NONE) {
            if (mode == Mode.LATE) {
                alert = ActionOutcome.SUPPRESSED;
                details.add("push held: matched on a late re-classification");
            } else {
                PushOutcome pushed =
                        notificationService.pushAutomation(automation, message, mode == Mode.TEST);
                alert = outcomeOf(pushed);
                switch (pushed) {
                    case SHADOW_MODE -> details.add("push held: shadow mode");
                    case QUIET_HOURS -> details.add("push held: quiet hours");
                    case FAILED -> details.add("push failed: ntfy refused");
                    case DELIVERED -> {}
                }
            }
        }

        ActionOutcome webhook = ActionOutcome.NONE;
        if (automation.hasWebhook()) {
            Optional<String> failure =
                    webhookClient.post(
                            automation.getWebhookUrl(),
                            WebhookPayload.of(automation, message, mode == Mode.TEST, clock));
            webhook = failure.isEmpty() ? ActionOutcome.DELIVERED : ActionOutcome.FAILED;
            failure.ifPresent(reason -> details.add("webhook: " + reason));
        }

        log.info(
                "Automation '{}' fired{}: alert={}, webhook={}",
                automation.getName(),
                mode == Mode.TEST ? " (test)" : "",
                alert.wire(),
                webhook.wire());

        return runRepository.save(
                AutomationRunEntity.builder()
                        .automation(automation)
                        .message(message)
                        .firedAt(Instant.now(clock))
                        .alertOutcome(alert)
                        .webhookOutcome(webhook)
                        .detail(details.isEmpty() ? null : String.join("; ", details))
                        .build());
    }

    private static ActionOutcome outcomeOf(PushOutcome pushed) {
        return switch (pushed) {
            case DELIVERED -> ActionOutcome.DELIVERED;
            case FAILED -> ActionOutcome.FAILED;
            case SHADOW_MODE, QUIET_HOURS -> ActionOutcome.SUPPRESSED;
        };
    }

    private AutomationEntity find(UUID id) {
        return automationRepository
                .findById(id)
                .orElseThrow(() -> new AutomationNotFoundException(id));
    }

    private void ensureUniqueName(String name, UUID self) {
        automationRepository
                .findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(
                        other -> {
                            throw new DuplicateAutomationException(name);
                        });
    }

    private static void ensureHasAction(AutomationAlert alert, String webhookUrl) {
        if (alert == AutomationAlert.NONE && blankToNull(webhookUrl) == null) {
            throw new AutomationWithoutActionException();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The sidecar only echoes ids it was sent, but this is still a string off the wire. */
    private static Optional<UUID> parse(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private enum Mode {
        LIVE,
        LATE,
        TEST
    }

    /**
     * What a webhook receives.
     *
     * <p>The same three fields the classifier was allowed, plus the verdict — never the snippet. A
     * webhook can be anything the user points it at, and the body text is the one part of a mail
     * Hermes otherwise keeps to itself.
     */
    public record WebhookPayload(
            String event, boolean test, Instant firedAt, Automation automation, Message message) {

        public record Automation(UUID id, String name, String trigger) {}

        public record Message(
                UUID id,
                String sender,
                String subject,
                String summary,
                Priority priority,
                String category,
                Instant receivedAt,
                String link) {}

        static WebhookPayload of(
                AutomationEntity automation, MessageEntity message, boolean test, Clock clock) {
            return new WebhookPayload(
                    "automation.fired",
                    test,
                    Instant.now(clock),
                    new Automation(
                            automation.getId(), automation.getName(), automation.getTrigger()),
                    message == null
                            ? null
                            : new Message(
                                    message.getId(),
                                    message.getSender(),
                                    message.getSubject(),
                                    message.getSummary(),
                                    message.getPriority(),
                                    message.getCategory() == null
                                            ? null
                                            : message.getCategory().getName(),
                                    message.getReceivedAt(),
                                    message.getProvider().deepLink(message.getExternalId())));
        }
    }
}
