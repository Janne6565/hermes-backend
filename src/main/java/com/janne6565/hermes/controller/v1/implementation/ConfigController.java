package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.controller.v1.schema.ConfigApi;
import com.janne6565.hermes.model.core.ConfigDto;
import com.janne6565.hermes.services.auth.GoogleOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfigController implements ConfigApi {

    private final HermesProperties properties;

    @Override
    public ResponseEntity<ConfigDto> current() {
        HermesProperties.Digest digest = properties.getDigest();
        HermesProperties.QuietHours quietHours = properties.getQuietHours();
        HermesProperties.Ntfy ntfy = properties.getNtfy();
        HermesProperties.Gmail gmail = properties.getGmail();

        return ResponseEntity.ok(
                new ConfigDto(
                        properties.getTimezone().getId(),
                        properties.isShadowMode(),
                        new ConfigDto.DigestConfig(
                                digest.getSendTime(),
                                digest.isIncludeNoise(),
                                digest.isSkipWhenEmpty()),
                        new ConfigDto.QuietHoursConfig(
                                quietHours.isEnabled(),
                                quietHours.getStart(),
                                quietHours.getEnd(),
                                quietHours.isAllowSecurityAlerts()),
                        new ConfigDto.NtfyConfig(
                                ntfy.getTopic(), "urgent", !ntfy.getToken().isBlank()),
                        new ConfigDto.DataConfig(
                                GoogleOAuthService.SCOPE,
                                gmail.getSnippetLength(),
                                properties.getRetention().getMessageDays(),
                                gmail.getPollInterval().toSeconds(),
                                properties.getSidecar().isEnabled())));
    }
}
