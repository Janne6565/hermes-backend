package com.janne6565.hermes.services.classification;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MessageDto;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.exception.MessageNotFoundException;
import com.janne6565.hermes.repository.MessageRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the message table: browsing, search and dismissal.
 *
 * <p>Search runs against the local Postgres index rather than Gmail, so it keeps working when sync
 * is down — which is exactly when you most want to look something up.
 */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageRepository messageRepository;
    private final HermesProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<MessageDto> search(
            Priority priority, LocalDate date, String sender, String query, int limit) {
        Specification<MessageEntity> specification =
                (root, criteriaQuery, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    if (priority != null) {
                        predicates.add(builder.equal(root.get("priority"), priority));
                    }
                    if (date != null) {
                        Instant from = date.atStartOfDay(properties.getTimezone()).toInstant();
                        Instant to =
                                date.plusDays(1).atStartOfDay(properties.getTimezone()).toInstant();
                        predicates.add(builder.between(root.get("receivedAt"), from, to));
                    }
                    if (sender != null && !sender.isBlank()) {
                        predicates.add(
                                builder.like(
                                        builder.lower(root.get("sender")),
                                        "%" + sender.toLowerCase(Locale.ROOT) + "%"));
                    }
                    if (query != null && !query.isBlank()) {
                        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
                        predicates.add(
                                builder.or(
                                        builder.like(builder.lower(root.get("subject")), like),
                                        builder.like(builder.lower(root.get("snippet")), like),
                                        builder.like(builder.lower(root.get("summary")), like)));
                    }
                    return builder.and(predicates.toArray(Predicate[]::new));
                };

        return messageRepository
                .findAll(specification, Sort.by(Sort.Direction.DESC, "receivedAt"))
                .stream()
                .limit(limit)
                .map(MessageDto::from)
                .toList();
    }

    /** High-priority items that are still open — the list the inbox screen leads with. */
    @Transactional(readOnly = true)
    public List<MessageDto> openHighPriority(int days) {
        Instant since = Instant.now(clock).minus(java.time.Duration.ofDays(days));
        return messageRepository
                .findByPriorityAndDismissedAtIsNullAndReceivedAtAfterOrderByReceivedAtDesc(
                        Priority.HIGH, since)
                .stream()
                .map(MessageDto::from)
                .toList();
    }

    @Transactional
    public MessageDto setDismissed(UUID messageId, boolean dismissed) {
        MessageEntity message =
                messageRepository
                        .findById(messageId)
                        .orElseThrow(() -> new MessageNotFoundException(messageId));
        message.setDismissedAt(dismissed ? Instant.now(clock) : null);
        return MessageDto.from(message);
    }

    @Transactional(readOnly = true)
    public long countBy(ClassifiedBy classifiedBy) {
        return messageRepository.countByClassifiedBy(classifiedBy);
    }
}
