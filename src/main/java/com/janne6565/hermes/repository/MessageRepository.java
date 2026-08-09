package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.Priority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository
        extends JpaRepository<MessageEntity, UUID>, JpaSpecificationExecutor<MessageEntity> {

    Optional<MessageEntity> findByGmailId(String gmailId);

    boolean existsByGmailId(String gmailId);

    List<MessageEntity> findByReceivedAtBetweenOrderByReceivedAtDesc(Instant from, Instant to);

    /** Most recent first, bounded — the sample a rule dry run is evaluated against. */
    List<MessageEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<MessageEntity> findByPriorityAndDismissedAtIsNullAndReceivedAtAfterOrderByReceivedAtDesc(
            Priority priority, Instant after);

    /** Rows the sidecar never got to; the nightly retry job re-classifies these. */
    List<MessageEntity> findByClassifiedByOrderByReceivedAtAsc(ClassifiedBy classifiedBy);

    long countByClassifiedBy(ClassifiedBy classifiedBy);

    long countByPriority(Priority priority);

    @Modifying
    @Query("delete from MessageEntity m where m.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}
