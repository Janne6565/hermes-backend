package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.model.core.RuleType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRuleEntity, UUID> {

    Optional<CategoryRuleEntity> findByTypeAndPattern(RuleType type, String pattern);

    List<CategoryRuleEntity> findByCategoryOrderByCreatedAtAsc(CategoryEntity category);
}
