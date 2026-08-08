package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.GoogleAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoogleAccountRepository extends JpaRepository<GoogleAccountEntity, Integer> {}
