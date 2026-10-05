package com.janne6565.hermes.model.action;

import com.janne6565.hermes.model.core.AutomationAlert;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Edit an automation. Omitted fields are left alone; an empty {@code webhookUrl} removes it. */
@Schema(description = "Edit an automation; omitted fields are left alone")
public record UpdateAutomationRequest(
        @Size(min = 1, max = 60) String name,
        @Size(min = 1, max = 300) String trigger,
        AutomationAlert alert,
        @Schema(description = "Empty string removes the webhook")
                @Size(max = 2000) @Pattern(regexp = "^(https?://\\S+)?$")
                String webhookUrl,
        Boolean enabled) {}
