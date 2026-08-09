package com.janne6565.hermes.services.alerts;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.model.core.AlertSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the "open in Grafana / SigNoz" link.
 *
 * <p>Returns null when the base URL is unconfigured. An absent link is honest — a link that 404s
 * would be worse than no link at all, and Hermes has no way to verify the target exists.
 */
@Component
@RequiredArgsConstructor
public class AlertLinkResolver {

    private final HermesProperties properties;

    public String urlFor(AlertEventEntity alert) {
        String base =
                alert.getSource() == AlertSource.SIGNOZ
                        ? properties.getAlerts().getSignozUrl()
                        : properties.getAlerts().getGrafanaUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        // Only the tool's own alert list is linked, not a per-alert deep link: the webhook payload
        // carries no stable UI identifier, so anything more specific would be guesswork.
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
