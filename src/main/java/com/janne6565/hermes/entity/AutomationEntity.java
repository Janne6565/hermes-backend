package com.janne6565.hermes.entity;

import com.janne6565.hermes.model.core.AutomationAlert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "When a mail like this arrives, do that."
 *
 * <p>The trigger is plain language and is judged by the classifier in the same turn as priority and
 * category. Like a category, an automation never moves priority — it only adds actions.
 */
@Entity
@Table(name = "automations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationEntity {

    @Id @Builder.Default private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String name;

    /** The natural-language trigger, e.g. "any mail about AWS pricing or billing". */
    @Column(name = "trigger_text", nullable = false, length = 300)
    private String trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AutomationAlert alert = AutomationAlert.NONE;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "fire_count", nullable = false)
    @Builder.Default
    private long fireCount = 0;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public boolean hasWebhook() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
