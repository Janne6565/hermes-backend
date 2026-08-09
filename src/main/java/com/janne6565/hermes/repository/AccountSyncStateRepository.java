package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.AccountSyncStateEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountSyncStateRepository extends JpaRepository<AccountSyncStateEntity, UUID> {}
