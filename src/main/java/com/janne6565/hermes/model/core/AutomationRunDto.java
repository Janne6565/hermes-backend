package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.AutomationRunEntity;
import com.janne6565.hermes.entity.MessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One firing of an automation and what each action did")
public record AutomationRunDto(
        UUID id,
        UUID automationId,
        String automationName,
        @Schema(description = "Absent for a test run") UUID messageId,
        String sender,
        String subject,
        Instant firedAt,
        ActionOutcome alert,
        ActionOutcome webhook,
        String detail) {

    public static AutomationRunDto from(AutomationRunEntity run) {
        MessageEntity message = run.getMessage();
        return new AutomationRunDto(
                run.getId(),
                run.getAutomation().getId(),
                run.getAutomation().getName(),
                message == null ? null : message.getId(),
                message == null ? null : message.getSender(),
                message == null ? null : message.getSubject(),
                run.getFiredAt(),
                run.getAlertOutcome(),
                run.getWebhookOutcome(),
                run.getDetail());
    }
}
