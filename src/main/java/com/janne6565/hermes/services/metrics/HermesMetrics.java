package com.janne6565.hermes.services.metrics;

import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.Priority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The handful of app-level counters behind the Hermes dashboard.
 *
 * <p>Every tag here is a closed enum, never a sender, subject or account: each label combination is
 * a series, and the cluster shares a 10,000-series budget. Thirteen series in total.
 *
 * <p>All of them are registered up front at zero. A counter that first appears at 1 is invisible to
 * {@code increase()}, and the first classifier fallback or failed push is exactly the one worth
 * seeing.
 */
@Component
public class HermesMetrics {

    private static final String TRIAGED = "hermes.messages.triaged";
    private static final String SYNC_FAILURES = "hermes.mail.sync.failures";
    private static final String NTFY_PUBLISHES = "hermes.ntfy.publishes";

    private final Map<Priority, Map<ClassifiedBy, Counter>> triaged = new EnumMap<>(Priority.class);
    private final Map<SyncStage, Counter> syncFailures = new EnumMap<>(SyncStage.class);
    private final Counter ntfyDelivered;
    private final Counter ntfyFailed;

    public HermesMetrics(MeterRegistry registry) {
        for (Priority priority : Priority.values()) {
            Map<ClassifiedBy, Counter> byStage = new EnumMap<>(ClassifiedBy.class);
            for (ClassifiedBy classifiedBy : ClassifiedBy.values()) {
                byStage.put(
                        classifiedBy,
                        Counter.builder(TRIAGED)
                                .description("New messages stored, by outcome and deciding stage")
                                .tag("priority", priority.wire())
                                .tag("classified_by", classifiedBy.wire())
                                .register(registry));
            }
            triaged.put(priority, byStage);
        }
        for (SyncStage stage : SyncStage.values()) {
            syncFailures.put(
                    stage,
                    Counter.builder(SYNC_FAILURES)
                            .description("Mail provider failures during the poll loop")
                            .tag("stage", stage.wire())
                            .register(registry));
        }
        ntfyDelivered = ntfyCounter(registry, "delivered");
        ntfyFailed = ntfyCounter(registry, "failed");
    }

    private static Counter ntfyCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(NTFY_PUBLISHES)
                .description("Push attempts to ntfy, by whether ntfy accepted them")
                .tag("outcome", outcome)
                .register(registry);
    }

    /** One freshly ingested message, with the verdict it was stored under. */
    public void messageTriaged(Priority priority, ClassifiedBy classifiedBy) {
        triaged.get(priority).get(classifiedBy).increment();
    }

    public void syncFailed(SyncStage stage) {
        syncFailures.get(stage).increment();
    }

    public void ntfyPublished(boolean delivered) {
        (delivered ? ntfyDelivered : ntfyFailed).increment();
    }

    /** Where in the poll loop a provider call failed. */
    public enum SyncStage {
        /** A whole mailbox's pass failed — listing, cursor, or token. */
        ACCOUNT,
        /** One message could not be fetched; the batch carried on past it. */
        MESSAGE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
