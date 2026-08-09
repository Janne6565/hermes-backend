package com.janne6565.hermes.services.categories;

import com.janne6565.hermes.client.SidecarClient;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.core.CategoryBackfillDto;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.repository.MessageRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorises mail that predates the feature.
 *
 * <p>Everything already stored was parked in the fallback bucket by the V6 migration rather than
 * guessed at, which was right for the arithmetic — a backfill invented inside a migration would
 * have reported "settled by rule" for mail no rule ever saw — and wrong for the screen, which then
 * showed one grey bar for a week. This is the missing other half.
 *
 * <p>Two passes, cheap first. Category rules cost nothing, so they run over the whole set before a
 * single token is spent; only what they miss reaches the classifier. On a mailbox where the seeded
 * patterns cover the monitoring and newsletter traffic that is a third of the calls saved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryBackfillService {

    private final MessageRepository messageRepository;
    private final CategoryMatcher categoryMatcher;
    private final CategoryService categoryService;
    private final SidecarClient sidecarClient;

    /**
     * @param limit how many messages may reach the classifier in one run. The rule pass is
     *     unbounded because it is free; this bounds only the part that costs credit, so a large
     *     mailbox can be worked through in several deliberate runs instead of one surprise bill.
     */
    @Transactional
    public CategoryBackfillDto run(int limit) {
        List<MessageEntity> pending = messageRepository.findUncategorised();
        if (pending.isEmpty()) {
            return new CategoryBackfillDto(0, 0, 0, 0, 0, null);
        }

        int byRule = 0;
        for (MessageEntity message : pending) {
            Optional<CategoryRuleEntity> hit = categoryMatcher.matchStored(message.getSender());
            if (hit.isPresent()) {
                message.setCategory(hit.get().getCategory());
                message.setCategorySource(CategorySource.RULE);
                message.setCategoryConfidence(null);
                byRule++;
            }
        }

        List<MessageEntity> remaining =
                pending.stream()
                        .filter(message -> message.getCategorySource() != CategorySource.RULE)
                        .toList();

        int byModel = 0;
        int skipped = 0;
        String stoppedBecause = null;
        List<String> vocabulary = categoryService.names();

        for (MessageEntity message : remaining) {
            if (byModel >= limit) {
                stoppedBecause = "limit";
                break;
            }
            Optional<SidecarClient.ClassificationResponse> response =
                    sidecarClient.classify(
                            new SidecarClient.ClassificationRequest(
                                    message.getSender(),
                                    message.getSubject(),
                                    message.getSnippet(),
                                    vocabulary));
            if (response.isEmpty()) {
                // Stop rather than grind through a dead sidecar marking everything unresolved —
                // the run is resumable, and a partial result the user can re-trigger beats a
                // complete one that is all fallback.
                stoppedBecause = "sidecar_unavailable";
                log.warn("Backfill stopped: sidecar unavailable after {} messages", byModel);
                break;
            }

            Optional<CategoryEntity> named =
                    categoryService.findByName(response.get().category());
            if (named.isEmpty()) {
                // The classifier declined or named something unknown. Left as-is so the next run
                // tries again, rather than stamped with a source that would hide it from this
                // query for good.
                skipped++;
                continue;
            }

            // The priority in this response is ignored. These messages were already triaged, in
            // some cases by a hard rule, and re-deciding that here would silently re-triage the
            // whole mailbox under the banner of a category backfill.
            message.setCategory(named.get());
            message.setCategorySource(CategorySource.LLM);
            message.setCategoryConfidence(response.get().categoryConfidence());
            message.setCategoryAlternative(
                    categoryService.findByName(response.get().categoryAlternative()).orElse(null));
            byModel++;
        }

        int outstanding = remaining.size() - byModel - skipped;
        log.info(
                "Category backfill: {} by rule, {} by model, {} skipped, {} left",
                byRule,
                byModel,
                skipped,
                outstanding);
        return new CategoryBackfillDto(
                pending.size(), byRule, byModel, skipped, Math.max(outstanding, 0), stoppedBecause);
    }
}
