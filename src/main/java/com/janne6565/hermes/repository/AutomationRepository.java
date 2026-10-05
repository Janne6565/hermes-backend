package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.AutomationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutomationRepository extends JpaRepository<AutomationEntity, UUID> {

    List<AutomationEntity> findAllByOrderByCreatedAtAsc();

    List<AutomationEntity> findByEnabledTrueOrderByCreatedAtAsc();

    long countByEnabledTrue();

    Optional<AutomationEntity> findByNameIgnoreCase(String name);
}
