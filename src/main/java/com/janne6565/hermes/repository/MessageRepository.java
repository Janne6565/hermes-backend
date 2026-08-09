package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MailProviderType;
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

    Optional<MessageEntity> findByProviderAndExternalId(
            MailProviderType provider, String externalId);

    boolean existsByProviderAndExternalId(MailProviderType provider, String externalId);

    List<MessageEntity> findByReceivedAtBetweenOrderByReceivedAtDesc(Instant from, Instant to);

    /** Most recent first, bounded — the sample a rule dry run is evaluated against. */
    List<MessageEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<MessageEntity> findByPriorityAndDismissedAtIsNullAndReceivedAtAfterOrderByReceivedAtDesc(
            Priority priority, Instant after);

    /** Rows the sidecar never got to; the nightly retry job re-classifies these. */
    List<MessageEntity> findByClassifiedByOrderByReceivedAtAsc(ClassifiedBy classifiedBy);

    /** The reporting window behind the categories screen. */
    List<MessageEntity> findByReceivedAtAfter(Instant since);

    List<MessageEntity> findByCategory(CategoryEntity category);

    /** The "needs a call" queue: the classifier named a category but was not sure enough. */
    List<MessageEntity> findByCategorySourceAndCategoryConfidenceLessThanOrderByReceivedAtDesc(
            CategorySource source, float threshold);

    /**
     * Everything the categoriser never settled: rows that predate the feature, plus anything left
     * in the fallback by a sidecar outage. Oldest first, so a bounded run works forward in a
     * predictable order rather than re-drawing the same recent slice each time.
     */
    @Query(
            "select m from MessageEntity m where m.categorySource is null"
                    + " or m.categorySource = com.janne6565.hermes.model.core.CategorySource.NONE"
                    + " order by m.receivedAt asc")
    List<MessageEntity> findUncategorised();

    @Query(
            "select count(m) from MessageEntity m where m.categorySource is null"
                    + " or m.categorySource = com.janne6565.hermes.model.core.CategorySource.NONE")
    long countUncategorised();

    long countByClassifiedBy(ClassifiedBy classifiedBy);

    long countByPriority(Priority priority);

    @Modifying
    @Query("delete from MessageEntity m where m.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}
