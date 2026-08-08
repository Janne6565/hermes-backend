package com.janne6565.hermes.services;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.classification.ClassificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The two nightly jobs: repair fallback classifications, then enforce retention. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceJobs {

    private final ClassificationService classificationService;
    private final MessageRepository messageRepository;
    private final HermesProperties properties;
    private final Clock clock;

    /** 03:00 — re-run the classifier over everything the sidecar missed while it was down. */
    @Scheduled(cron = "0 0 3 * * *", zone = "#{@hermesProperties.timezone.getId()}")
    public void retryFallbackClassifications() {
        classificationService.reclassifyFallbacks();
    }

    /**
     * 03:30 — delete classified messages past the retention window. Runs after the retry so a
     * message is never deleted while still awaiting its real classification.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "#{@hermesProperties.timezone.getId()}")
    @Transactional
    public void enforceRetention() {
        Instant cutoff =
                Instant.now(clock)
                        .minus(Duration.ofDays(properties.getRetention().getMessageDays()));
        int deleted = messageRepository.deleteReceivedBefore(cutoff);
        if (deleted > 0) {
            log.info("Retention: deleted {} messages received before {}", deleted, cutoff);
        }
    }
}
