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
        Instant notifiedAt,
        boolean dismissed,
        @Schema(description = "Deep link into the Gmail web client") String gmailUrl) {

    private static final String GMAIL_LINK = "https://mail.google.com/mail/u/0/#inbox/";

    public static MessageDto from(MessageEntity entity) {
        return new MessageDto(
                entity.getId(),
                entity.getGmailId(),
                entity.getSender(),
                displayName(entity.getSender()),
                entity.getSubject(),
                entity.getSnippet(),
                entity.getReceivedAt(),
                entity.getPriority(),
                entity.getSummary(),
                entity.getReason(),
                entity.getClassifiedBy(),
                entity.getNotifiedAt(),
                entity.getDismissedAt() != null,
                GMAIL_LINK + entity.getGmailId());
    }

    private static String displayName(String sender) {
        int open = sender.indexOf('<');
        String name = open > 0 ? sender.substring(0, open).trim() : sender.trim();
        return name.replaceAll("^\"|\"$", "");
    }
}
