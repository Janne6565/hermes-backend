package com.janne6565.hermes.services.automations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.client.NtfyClient;
import com.janne6565.hermes.client.WebhookClient;
import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AutomationEntity;
import com.janne6565.hermes.entity.AutomationRunEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.action.CreateAutomationRequest;
import com.janne6565.hermes.model.action.UpdateAutomationRequest;
import com.janne6565.hermes.model.core.ActionOutcome;
import com.janne6565.hermes.model.core.AutomationAlert;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.exception.AutomationLimitException;
import com.janne6565.hermes.model.exception.AutomationWithoutActionException;
import com.janne6565.hermes.repository.AutomationRepository;
import com.janne6565.hermes.repository.AutomationRunRepository;
import com.janne6565.hermes.services.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * An automation adds actions on top of the triage and must never be a way around its gates: shadow
 * mode always holds, quiet hours hold unless the user asked to be woken, and a late match never
 * pushes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutomationServiceTest {

    /** 02:00 in Berlin — inside the default 23:00 → 07:30 quiet hours. */
    private static final Clock NIGHT =
            Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock private AutomationRepository automationRepository;
    @Mock private AutomationRunRepository runRepository;
    @Mock private NtfyClient ntfyClient;
    @Mock private WebhookClient webhookClient;

    private final HermesProperties properties = new HermesProperties();
    private AutomationService service;
    private MessageEntity message;

    @BeforeEach
    void setUp() {
        properties.setShadowMode(false);
        properties.getQuietHours().setEnabled(true);
        NotificationService notifications = new NotificationService(ntfyClient, properties, NIGHT);
        service =
                new AutomationService(
                        automationRepository, runRepository, notifications, webhookClient, NIGHT);

        when(ntfyClient.publish(any())).thenReturn(true);
        when(webhookClient.post(anyString(), any())).thenReturn(Optional.empty());
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        message =
                MessageEntity.builder()
                        .provider(MailProviderType.GMAIL)
                        .externalId("gmail-1")
                        .sender("AWS <no-reply@aws.amazon.com>")
                        .subject("Changes to EC2 pricing")
                        .snippet("body text that must never leave in a webhook")
                        .receivedAt(Instant.parse("2026-10-04T23:58:00Z"))
                        .priority(Priority.NOISE)
                        .classifiedBy(ClassifiedBy.LLM)
                        .build();
    }

    private AutomationEntity stored(AutomationAlert alert, String webhook) {
        AutomationEntity automation =
                AutomationEntity.builder()
                        .name("AWS pricing " + alert)
                        .trigger("AWS pricing changes")
                        .alert(alert)
                        .webhookUrl(webhook)
                        .build();
        when(automationRepository.findById(automation.getId())).thenReturn(Optional.of(automation));
        return automation;
    }

    private AutomationRunEntity lastRun() {
        ArgumentCaptor<AutomationRunEntity> captor =
                ArgumentCaptor.forClass(AutomationRunEntity.class);
        verify(runRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anImportantAutomationPiercesQuietHoursAsAnUrgentPush() {
        AutomationEntity automation = stored(AutomationAlert.IMPORTANT, null);

        service.fire(message, List.of(automation.getId().toString()), false);

        ArgumentCaptor<NtfyClient.Notification> push =
                ArgumentCaptor.forClass(NtfyClient.Notification.class);
        verify(ntfyClient).publish(push.capture());
        assertThat(push.getValue().priority()).isEqualTo(NtfyClient.NtfyPriority.URGENT);
        assertThat(lastRun().getAlertOutcome()).isEqualTo(ActionOutcome.DELIVERED);
        assertThat(automation.getFireCount()).isEqualTo(1);
        // The priority is the triage's, untouched.
        assertThat(message.getPriority()).isEqualTo(Priority.NOISE);
    }

    @Test
    void aDirectAutomationIsHeldDuringQuietHours() {
        AutomationEntity automation = stored(AutomationAlert.DIRECT, null);

        service.fire(message, List.of(automation.getId().toString()), false);

        verify(ntfyClient, never()).publish(any());
        AutomationRunEntity run = lastRun();
        assertThat(run.getAlertOutcome()).isEqualTo(ActionOutcome.SUPPRESSED);
        assertThat(run.getDetail()).contains("quiet hours");
    }

    @Test
    void shadowModeHoldsEvenAnImportantAutomation() {
        properties.setShadowMode(true);
        AutomationEntity automation = stored(AutomationAlert.IMPORTANT, null);

        service.fire(message, List.of(automation.getId().toString()), false);

        verify(ntfyClient, never()).publish(any());
        assertThat(lastRun().getAlertOutcome()).isEqualTo(ActionOutcome.SUPPRESSED);
    }

    @Test
    void aLateMatchCallsTheWebhookButNeverPushes() {
        AutomationEntity automation =
                stored(AutomationAlert.IMPORTANT, "https://hooks.example/aws");

        service.fire(message, List.of(automation.getId().toString()), true);

        verify(ntfyClient, never()).publish(any());
        verify(webhookClient).post(eq("https://hooks.example/aws"), any());
        AutomationRunEntity run = lastRun();
        assertThat(run.getAlertOutcome()).isEqualTo(ActionOutcome.SUPPRESSED);
        assertThat(run.getWebhookOutcome()).isEqualTo(ActionOutcome.DELIVERED);
    }

    @Test
    void theWebhookPayloadCarriesTheVerdictButNotTheBody() {
        AutomationEntity automation = stored(AutomationAlert.NONE, "https://hooks.example/aws");

        service.fire(message, List.of(automation.getId().toString()), false);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(webhookClient).post(anyString(), payload.capture());
        AutomationService.WebhookPayload body =
                (AutomationService.WebhookPayload) payload.getValue();
        assertThat(body.test()).isFalse();
        assertThat(body.message().subject()).isEqualTo("Changes to EC2 pricing");
        assertThat(body.message().link()).endsWith("gmail-1");
        assertThat(body.toString()).doesNotContain("must never leave");
    }

    @Test
    void aFailingWebhookIsRecordedNotThrown() {
        AutomationEntity automation = stored(AutomationAlert.NONE, "https://hooks.example/aws");
        when(webhookClient.post(anyString(), any())).thenReturn(Optional.of("HTTP 502"));

        service.fire(message, List.of(automation.getId().toString()), false);

        AutomationRunEntity run = lastRun();
        assertThat(run.getWebhookOutcome()).isEqualTo(ActionOutcome.FAILED);
        assertThat(run.getDetail()).contains("HTTP 502");
    }

    @Test
    void unknownPausedAndMalformedIdsFireNothing() {
        AutomationEntity paused = stored(AutomationAlert.IMPORTANT, null);
        paused.setEnabled(false);
        when(automationRepository.findById(any(UUID.class)))
                .thenAnswer(
                        invocation ->
                                paused.getId().equals(invocation.getArgument(0))
                                        ? Optional.of(paused)
                                        : Optional.empty());

        service.fire(
                message,
                List.of(paused.getId().toString(), UUID.randomUUID().toString(), "not-a-uuid"),
                false);

        verify(ntfyClient, never()).publish(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void aRepeatedIdFiresOnce() {
        AutomationEntity automation = stored(AutomationAlert.IMPORTANT, null);
        String id = automation.getId().toString();

        service.fire(message, List.of(id, id), false);

        assertThat(automation.getFireCount()).isEqualTo(1);
    }

    @Test
    void anAutomationThatWouldDoNothingIsRejected() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateAutomationRequest(
                                                "Nothing", "anything", AutomationAlert.NONE, " ")))
                .isInstanceOf(AutomationWithoutActionException.class);
    }

    @Test
    void removingTheLastActionByEditIsRejectedToo() {
        AutomationEntity automation = stored(AutomationAlert.NONE, "https://hooks.example/aws");

        assertThatThrownBy(
                        () ->
                                service.update(
                                        automation.getId(),
                                        new UpdateAutomationRequest(null, null, null, "", null)))
                .isInstanceOf(AutomationWithoutActionException.class);
    }

    @Test
    void theEnabledLimitIsEnforcedOnCreate() {
        when(automationRepository.countByEnabledTrue())
                .thenReturn((long) AutomationService.MAX_ENABLED);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateAutomationRequest(
                                                "One too many",
                                                "anything",
                                                AutomationAlert.DIRECT,
                                                null)))
                .isInstanceOf(AutomationLimitException.class);
    }
}
