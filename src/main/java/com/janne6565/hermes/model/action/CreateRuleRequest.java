package com.janne6565.hermes.model.action;

import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A new hard rule, evaluated before the classifier")
public record CreateRuleRequest(
        @NotNull RuleType type,
        @Schema(example = "*.uni-potsdam.de") @NotBlank @Size(max = 512) String pattern,
        @NotNull Priority priority) {}
