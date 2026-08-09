package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.DigestEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DigestRepository extends JpaRepository<DigestEntity, UUID> {

    Optional<DigestEntity> findByDate(LocalDate date);

    List<DigestEntity> findByDateBetween(LocalDate from, LocalDate to);
}
