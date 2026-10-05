package com.janne6565.hermes.model.action;

import com.janne6565.hermes.model.core.AutomationAlert;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new automation. At least one action — an alert other than {@code none}, or a webhook — is
 * required; the service rejects an automation that would fire and do nothing.
 */
@Schema(description = "A natural-language trigger and what to do when a mail matches it")
public record CreateAutomationRequest(
        @NotBlank @Size(max = 60) String name,
        @Schema(example = "Any mail about AWS pricing, billing or cost changes")
                @NotBlank @Size(max = 300) String trigger,
        @NotNull AutomationAlert alert,
        @Schema(description = "Optional http(s) URL that receives a JSON POST when this fires")
                @Size(max = 2000) @Pattern(regexp = "^(https?://\\S+)?$")
                String webhookUrl) {}
