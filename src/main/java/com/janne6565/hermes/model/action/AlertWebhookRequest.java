package com.janne6565.hermes.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Normalised alert webhook body. Grafana contact points and SigNoz alert channels post different
 * shapes; the adapters in the controller flatten both into this before it reaches the service.
 */
@Schema(description = "An alert delivered by Grafana or SigNoz over the webhook intake")
public record AlertWebhookRequest(
        @Schema(example = "grafana") @NotBlank String source,
        @Schema(example = "critical", description = "critical | error | warning | info | resolved")
                String severity,
        @Schema(example = "PodCrashLooping — drei-alben/api") @NotBlank String title,
        @Schema(example = "drei-alben", description = "Application the alert is about") String app,
        @Schema(description = "Stable id for this alert instance; re-fires update in place")
                String fingerprint) {}
