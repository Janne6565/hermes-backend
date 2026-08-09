package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.AlertEventEntity;
import com.janne6565.hermes.model.core.AlertSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEventEntity, UUID> {

    List<AlertEventEntity> findByReceivedAtBetweenOrderByReceivedAtDesc(Instant from, Instant to);

    List<AlertEventEntity> findByResolvedAtIsNullOrderByReceivedAtDesc();

    Optional<AlertEventEntity> findByFingerprintAndResolvedAtIsNull(String fingerprint);

    /** Distinguishes a source that is quiet today from one that was never wired up. */
    Optional<AlertEventEntity> findFirstBySourceOrderByReceivedAtDesc(AlertSource source);

    Optional<AlertEventEntity> findFirstByFingerprintAndSnoozedUntilAfterOrderBySnoozedUntilDesc(
            String fingerprint, Instant now);
}
