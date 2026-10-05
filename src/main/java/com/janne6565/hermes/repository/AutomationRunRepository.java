package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.AutomationRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AutomationRunRepository extends JpaRepository<AutomationRunEntity, UUID> {

    /** Fetch-joined: the screen renders the automation name and the mail's subject on every row. */
    @Query(
            "select r from AutomationRunEntity r join fetch r.automation left join fetch r.message"
                    + " order by r.firedAt desc")
    List<AutomationRunEntity> findRecent(Pageable page);
}
