package com.janne6565.hermes.model.core;

import com.janne6565.hermes.entity.MessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A classified message as shown in the inbox, digest and search results")
public record MessageDto(
        UUID id,
        String gmailId,
        String sender,
        String senderName,
        String subject,
        String snippet,
        Instant receivedAt,
        Priority priority,
        String summary,
        String reason,
        ClassifiedBy classifiedBy,
        @Schema(description = "The one topic bucket this message is in, or null for legacy rows")
                String category,
        @Schema(description = "Hex swatch of that category") String categoryColor,
        CategorySource categorySource,
        Instant notifiedAt,
        boolean dismissed,
        @Schema(description = "Deep link into the Gmail web client") String gmailUrl,
        @Schema(description = "Short origin label, or null — currently only \"infra\"")
                String tag) {

    /**
     * Senders that mean "this is machine-generated infrastructure mail".
     *
     * <p>A heuristic, but one over the sender address — a stable fact — rather than over the
     * classifier's free-text reason, which is model output and changes wording between runs.
     */
    private static final String[] INFRA_SENDERS = {
        "grafana", "signoz", "argocd", "alertmanager", "prometheus"
    };

    public static MessageDto from(MessageEntity entity) {
        return new MessageDto(
                entity.getId(),
                entity.getExternalId(),
                entity.getSender(),
                displayName(entity.getSender()),
                entity.getSubject(),
                entity.getSnippet(),
                entity.getReceivedAt(),
                entity.getPriority(),
                entity.getSummary(),
                entity.getReason(),
                entity.getClassifiedBy(),
                entity.getCategory() == null ? null : entity.getCategory().getName(),
                entity.getCategory() == null ? null : entity.getCategory().getColor(),
                entity.getCategorySource(),
                entity.getNotifiedAt(),
                entity.getDismissedAt() != null,
                entity.getProvider().deepLink(entity.getExternalId()),
                tagOf(entity.getSender()));
    }

    private static String tagOf(String sender) {
        String lower = sender.toLowerCase(java.util.Locale.ROOT);
        for (String known : INFRA_SENDERS) {
            if (lower.contains(known)) {
                return "infra";
            }
        }
        return null;
    }

    private static String displayName(String sender) {
        int open = sender.indexOf('<');
        String name = open > 0 ? sender.substring(0, open).trim() : sender.trim();
        return name.replaceAll("^\"|\"$", "");
    }
}
