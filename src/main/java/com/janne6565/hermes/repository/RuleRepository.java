package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.RuleEntity;
import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.model.core.RuleType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RuleRepository extends JpaRepository<RuleEntity, UUID> {

    List<RuleEntity> findByEnabledTrue();

    List<RuleEntity> findAllByOrderByCreatedAtDesc();

    Optional<RuleEntity> findByTypeAndPattern(RuleType type, String pattern);

    /** Rules the user's own corrections produced, newest first. */
    List<RuleEntity> findBySourceOrderByCreatedAtDesc(RuleSource source);

    long countBySource(RuleSource source);
}
