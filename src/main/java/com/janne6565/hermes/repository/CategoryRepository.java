package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.CategoryEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    List<CategoryEntity> findAllByOrderByPositionAscNameAsc();

    Optional<CategoryEntity> findByNameIgnoreCase(String name);

    /** The bucket everything unresolved lands in; guaranteed to exist by the V6 seed. */
    Optional<CategoryEntity> findByFallbackTrue();
}
