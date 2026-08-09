package com.janne6565.hermes.repository;

import com.janne6565.hermes.entity.MailAccountEntity;
import com.janne6565.hermes.model.core.MailProviderType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailAccountRepository extends JpaRepository<MailAccountEntity, UUID> {

    List<MailAccountEntity> findAllByOrderByConnectedAtAsc();

    Optional<MailAccountEntity> findByProviderAndEmail(MailProviderType provider, String email);

    List<MailAccountEntity> findByProvider(MailProviderType provider);
}
