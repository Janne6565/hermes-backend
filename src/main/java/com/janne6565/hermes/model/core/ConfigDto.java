package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

/**
 * The server's effective configuration, read-only.
 *
 * <p>The settings screen presents itself as a mirror of the ConfigMap, so it has to read the values
 * the service actually booted with. A screen that renders hardcoded defaults is worse than one that
 * renders nothing: it keeps looking right after the ConfigMap changes.
 *
 * <p>Nothing secret appears here — only whether a credential is present, never its value.
 */
@Schema(description = "Effective server configuration, as booted")
public record ConfigDto(
        String timezone,
        @Schema(description = "Classify and store everything, push nothing") boolean shadowMode,
        DigestConfig digest,
        QuietHoursConfig quietHours,
        NtfyConfig ntfy,
        DataConfig data) {

    public record DigestConfig(LocalTime sendTime, boolean includeNoise, boolean skipWhenEmpty) {}

    public record QuietHoursConfig(
            boolean enabled, LocalTime start, LocalTime end, boolean allowSecurityAlerts) {}

    @Schema(description = "Where pushes go. The token itself is never exposed.")
    public record NtfyConfig(
            String topic,
            @Schema(description = "ntfy priority used for high-priority mail") String highPriority,
            boolean tokenConfigured) {}

    public record DataConfig(
            String gmailScope,
            @Schema(description = "Characters of plaintext sent to the classifier")
                    int snippetLength,
            int retentionDays,
            long pollIntervalSeconds,
            @Schema(description = "Whether the classifier sidecar is in the pipeline at all")
                    boolean classifierEnabled) {}
}
