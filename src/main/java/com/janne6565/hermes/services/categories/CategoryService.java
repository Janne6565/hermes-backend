package com.janne6565.hermes.services.categories;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.action.AssignCategoryRequest;
import com.janne6565.hermes.model.action.CreateCategoryRequest;
import com.janne6565.hermes.model.action.UpdateCategoryRequest;
import com.janne6565.hermes.model.core.BackfillStatusDto;
import com.janne6565.hermes.model.core.CategoryDto;
import com.janne6565.hermes.model.core.CategoryOverviewDto;
import com.janne6565.hermes.model.core.CategoryRuleDto;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleSource;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.model.exception.BuiltinCategoryException;
import com.janne6565.hermes.model.exception.CategoryNotFoundException;
import com.janne6565.hermes.model.exception.CategoryRuleNotFoundException;
import com.janne6565.hermes.model.exception.DuplicateCategoryException;
import com.janne6565.hermes.model.exception.MessageNotFoundException;
import com.janne6565.hermes.repository.CategoryRepository;
import com.janne6565.hermes.repository.CategoryRuleRepository;
import com.janne6565.hermes.repository.MessageRepository;
import com.janne6565.hermes.services.rules.RuleEngine;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything behind the categories screen: the shares, the unsure queue and the corrections. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    /**
     * Upper bound on the rules returned per category. The table truncates visually anyway and the
     * detail view is a list, not a spreadsheet; this only stops a runaway category from bloating
     * every poll of the overview.
     */
    private static final int RULES_LIMIT = 50;

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final MessageRepository messageRepository;
    private final HermesProperties properties;

    /** The classifier's vocabulary — the exact names the sidecar is allowed to answer with. */
    @Transactional(readOnly = true)
    public List<String> names() {
        return categoryRepository.findAllByOrderByPositionAscNameAsc().stream()
                .filter(category -> !category.isFallback())
                .map(CategoryEntity::getName)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CategoryEntity> findByName(String name) {
        return name == null || name.isBlank()
                ? Optional.empty()
                : categoryRepository.findByNameIgnoreCase(name.trim());
    }

    @Transactional(readOnly = true)
    public Optional<CategoryEntity> fallback() {
        return categoryRepository.findByFallbackTrue();
    }

    @Transactional(readOnly = true)
    public CategoryOverviewDto overview(int windowDays, BackfillStatusDto backfill) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        List<CategoryEntity> categories = categoryRepository.findAllByOrderByPositionAscNameAsc();
        List<MessageEntity> window = messageRepository.findByReceivedAtAfter(since);

        Map<UUID, List<MessageEntity>> byCategory =
                window.stream()
                        .filter(message -> message.getCategory() != null)
                        .collect(Collectors.groupingBy(message -> message.getCategory().getId()));

        Map<UUID, List<CategoryRuleDto>> patterns = rulesByCategory();

        List<CategoryDto> rows =
                categories.stream()
                        .map(
                                category ->
                                        row(
                                                category,
                                                byCategory.getOrDefault(
                                                        category.getId(), List.of()),
                                                window.size(),
                                                patterns.getOrDefault(category.getId(), List.of())))
                        .toList();

        return new CategoryOverviewDto(
                windowDays, rows, mix(window), unsure(), recentCorrections(window), backfill);
    }

    private CategoryDto row(
            CategoryEntity category,
            List<MessageEntity> messages,
            int windowTotal,
            List<CategoryRuleDto> rules) {
        if (messages.isEmpty()) {
            return CategoryDto.empty(category, rules);
        }
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.isBuiltin(),
                category.isFallback(),
                messages.size(),
                windowTotal == 0 ? 0d : (double) messages.size() / windowTotal,
                typicalPriority(messages),
                rules,
                (int)
                        messages.stream()
                                .filter(m -> m.getCategorySource() == CategorySource.USER)
                                .count());
    }

    /**
     * The priority this category's mail actually tends to get.
     *
     * <p>Observed rather than configured, on purpose. A category with a *stored* default priority
     * would be a second, quieter way to decide what interrupts the user, and the one invariant this
     * feature has is that categories never do that. So the column reports what happened.
     */
    private static Priority typicalPriority(List<MessageEntity> messages) {
        return messages.stream()
                .collect(Collectors.groupingBy(MessageEntity::getPriority, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private Map<UUID, List<CategoryRuleDto>> rulesByCategory() {
        return categoryRuleRepository.findAll().stream()
                .collect(
                        Collectors.groupingBy(
                                rule -> rule.getCategory().getId(),
                                Collectors.collectingAndThen(
                                        Collectors.toList(),
                                        rules ->
                                                rules.stream()
                                                        .sorted(
                                                                Comparator.comparing(
                                                                                CategoryRuleEntity
                                                                                        ::getCreatedAt)
                                                                        // Seeded rules share a
                                                                        // timestamp to the
                                                                        // microsecond, so without
                                                                        // this the column reorders
                                                                        // itself between reads.
                                                                        .thenComparing(
                                                                                CategoryRuleEntity
                                                                                        ::getPattern))
                                                        .map(CategoryRuleDto::from)
                                                        .limit(RULES_LIMIT)
                                                        .toList())));
    }

    private CategoryOverviewDto.Mix mix(List<MessageEntity> window) {
        float threshold = properties.getCategories().getConfidenceThreshold();
        int byRule = 0;
        int byModel = 0;
        int lowConfidence = 0;
        int byUser = 0;
        int unresolved = 0;
        for (MessageEntity message : window) {
            CategorySource source = message.getCategorySource();
            if (source == null) {
                unresolved++;
                continue;
            }
            switch (source) {
                case RULE -> byRule++;
                case USER -> byUser++;
                case NONE -> unresolved++;
                case LLM -> {
                    // Split on the same threshold the unsure queue uses, so the "asked you" number
                    // and the length of that queue cannot tell different stories.
                    if (isLowConfidence(message, threshold)) {
                        lowConfidence++;
                    } else {
                        byModel++;
                    }
                }
            }
        }
        return new CategoryOverviewDto.Mix(byRule, byModel, lowConfidence, byUser, unresolved);
    }

    private static boolean isLowConfidence(MessageEntity message, float threshold) {
        Float confidence = message.getCategoryConfidence();
        return confidence != null && confidence < threshold;
    }

    private List<CategoryOverviewDto.UnsureDto> unsure() {
        float threshold = properties.getCategories().getConfidenceThreshold();
        int limit = properties.getCategories().getUnsureLimit();
        return messageRepository
                .findByCategorySourceAndCategoryConfidenceLessThanOrderByReceivedAtDesc(
                        CategorySource.LLM, threshold)
                .stream()
                .limit(limit)
                .map(
                        message ->
                                new CategoryOverviewDto.UnsureDto(
                                        message.getId(),
                                        message.getSender(),
                                        message.getSubject(),
                                        message.getReceivedAt(),
                                        message.getCategoryConfidence(),
                                        ref(message.getCategory()),
                                        ref(message.getCategoryAlternative())))
                .toList();
    }

    private static CategoryOverviewDto.CategoryRef ref(CategoryEntity category) {
        return category == null
                ? null
                : new CategoryOverviewDto.CategoryRef(
                        category.getId(), category.getName(), category.getColor());
    }

    private static List<CategoryOverviewDto.CorrectionDto> recentCorrections(
            List<MessageEntity> window) {
        return window.stream()
                .filter(message -> message.getCategoryCorrectedAt() != null)
                .sorted(Comparator.comparing(MessageEntity::getCategoryCorrectedAt).reversed())
                .limit(5)
                .map(
                        message ->
                                new CategoryOverviewDto.CorrectionDto(
                                        message.getId(),
                                        message.getSubject(),
                                        message.getCategory() == null
                                                ? null
                                                : message.getCategory().getName(),
                                        message.getCategoryPrevious() == null
                                                ? null
                                                : message.getCategoryPrevious().getName(),
                                        message.getCategoryCorrectedAt()))
                .toList();
    }

    @Transactional
    public CategoryDto create(CreateCategoryRequest request) {
        String name = request.name().trim();
        categoryRepository
                .findByNameIgnoreCase(name)
                .ifPresent(
                        existing -> {
                            throw new DuplicateCategoryException(name);
                        });

        int nextPosition =
                categoryRepository.findAllByOrderByPositionAscNameAsc().stream()
                                .mapToInt(CategoryEntity::getPosition)
                                .max()
                                .orElse(0)
                        + 1;

        CategoryEntity created =
                categoryRepository.save(
                        CategoryEntity.builder()
                                .name(name)
                                .color(request.color())
                                .builtin(false)
                                .fallback(false)
                                .position(nextPosition)
                                .build());
        log.info("Created category '{}'", name);
        return CategoryDto.empty(created, List.of());
    }

    /**
     * Renames and/or recolours a category.
     *
     * <p>Built-ins are renameable, unlike deletable. Deleting one shrinks what the classifier is
     * allowed to answer; renaming only changes the label it answers with, and the vocabulary is
     * sent per request, so the next classification already uses the new name. The messages already
     * filed under it keep their id-based link and need no migration.
     */
    @Transactional
    public CategoryDto rename(UUID categoryId, UpdateCategoryRequest request) {
        CategoryEntity category =
                categoryRepository
                        .findById(categoryId)
                        .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        if (request.name() != null) {
            String name = request.name().trim();
            categoryRepository
                    .findByNameIgnoreCase(name)
                    .filter(other -> !other.getId().equals(categoryId))
                    .ifPresent(
                            other -> {
                                throw new DuplicateCategoryException(name);
                            });
            log.info("Renamed category '{}' to '{}'", category.getName(), name);
            category.setName(name);
        }
        if (request.color() != null) {
            category.setColor(request.color());
        }

        return CategoryDto.empty(category, rulesOf(category));
    }

    /**
     * Deletes a user-made category and returns everything it held to the fallback.
     *
     * <p>The messages are re-parked rather than left pointing at nothing, because "every message
     * has exactly one category" is the promise the screen makes.
     */
    @Transactional
    public void delete(UUID categoryId) {
        CategoryEntity category =
                categoryRepository
                        .findById(categoryId)
                        .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        if (category.isBuiltin()) {
            throw new BuiltinCategoryException(category.getName());
        }

        CategoryEntity fallback = fallback().orElse(null);
        for (MessageEntity message : messageRepository.findByCategory(category)) {
            message.setCategory(fallback);
            message.setCategorySource(CategorySource.NONE);
            message.setCategoryConfidence(null);
        }
        // The rules go with it (ON DELETE CASCADE), which is the point: a deleted category should
        // stop claiming new mail, not keep matching into a hole.
        categoryRepository.delete(category);
        log.info("Deleted category '{}'", category.getName());
    }

    /**
     * Applies the user's verdict to one message and, unless told otherwise, teaches the rule that
     * follows from it so the same sender is never asked about twice.
     *
     * <p>Priority is deliberately untouched — see {@link AssignCategoryRequest}.
     */
    @Transactional
    public CategoryDto assign(AssignCategoryRequest request) {
        MessageEntity message =
                messageRepository
                        .findById(request.messageId())
                        .orElseThrow(() -> new MessageNotFoundException(request.messageId()));
        CategoryEntity category =
                categoryRepository
                        .findById(request.categoryId())
                        .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));

        CategoryEntity previous = message.getCategory();
        // Two conditions, for two different reasons.
        //
        // Not already USER: a second correction of the same message should still show what the
        // *classifier* said, not the user's own last answer.
        //
        // Actually different: confirming the guess is a real action — it writes the rule and it
        // counts — but recording it as a change renders in the trail as "Newsletters (was
        // Newsletters)", which reads as a bug rather than as a confirmation.
        if (message.getCategorySource() != CategorySource.USER
                && (previous == null || !previous.getId().equals(category.getId()))) {
            message.setCategoryPrevious(previous);
        }
        message.setCategory(category);
        message.setCategorySource(CategorySource.USER);
        message.setCategoryConfidence(null);
        message.setCategoryCorrectedAt(Instant.now());

        if (request.shouldLearn()) {
            RuleType type = request.shouldApplyToDomain() ? RuleType.DOMAIN : RuleType.SENDER;
            String pattern =
                    request.shouldApplyToDomain()
                            ? "*." + RuleEngine.domainOf(message.getSender())
                            : RuleEngine.emailAddress(message.getSender());
            upsertRule(type, pattern, category);
            log.info("Category feedback: {} '{}' -> {}", type.wire(), pattern, category.getName());
        }

        return CategoryDto.empty(category, rulesOf(category));
    }

    private List<CategoryRuleDto> rulesOf(CategoryEntity category) {
        return categoryRuleRepository.findByCategoryOrderByCreatedAtAsc(category).stream()
                .map(CategoryRuleDto::from)
                .limit(RULES_LIMIT)
                .toList();
    }

    /**
     * Removes one pattern without touching the category it fed.
     *
     * <p>Mail already filed by it keeps its category: the rule explains how a message got there,
     * not where it belongs, and silently re-opening a hundred settled messages because a pattern
     * was retired would be a far larger action than the button implies.
     */
    @Transactional
    public void deleteRule(UUID ruleId) {
        CategoryRuleEntity rule =
                categoryRuleRepository
                        .findById(ruleId)
                        .orElseThrow(() -> new CategoryRuleNotFoundException(ruleId));
        log.info(
                "Deleted category rule {} '{}' from {}",
                rule.getType().wire(),
                rule.getPattern(),
                rule.getCategory().getName());
        categoryRuleRepository.delete(rule);
    }

    /**
     * A pattern belongs to one category, so a correction that contradicts an earlier one moves the
     * existing rule instead of erroring — the user changing their mind is the normal case here, not
     * a conflict to report.
     */
    private void upsertRule(RuleType type, String pattern, CategoryEntity category) {
        categoryRuleRepository
                .findByTypeAndPattern(type, pattern)
                .ifPresentOrElse(
                        existing -> existing.setCategory(category),
                        () ->
                                categoryRuleRepository.save(
                                        CategoryRuleEntity.builder()
                                                .category(category)
                                                .type(type)
                                                .pattern(pattern)
                                                .source(RuleSource.FEEDBACK)
                                                .build()));
    }
}
