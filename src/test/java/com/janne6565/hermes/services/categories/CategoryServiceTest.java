package com.janne6565.hermes.services.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.entity.CategoryEntity;
import com.janne6565.hermes.entity.CategoryRuleEntity;
import com.janne6565.hermes.entity.MessageEntity;
import com.janne6565.hermes.model.action.AssignCategoryRequest;
import com.janne6565.hermes.model.action.UpdateCategoryRequest;
import com.janne6565.hermes.model.core.CategorySource;
import com.janne6565.hermes.model.core.ClassifiedBy;
import com.janne6565.hermes.model.core.MailProviderType;
import com.janne6565.hermes.model.core.Priority;
import com.janne6565.hermes.model.core.RuleType;
import com.janne6565.hermes.model.exception.CategoryRuleNotFoundException;
import com.janne6565.hermes.model.exception.DuplicateCategoryException;
import com.janne6565.hermes.repository.CategoryRepository;
import com.janne6565.hermes.repository.CategoryRuleRepository;
import com.janne6565.hermes.repository.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The invariant this feature stands on: recategorising a message says something about its topic and
 * nothing about whether it interrupts. If a correction could move a mail's priority, the categories
 * screen would be a second, quieter way to silence the mailbox.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryRuleRepository categoryRuleRepository;
    @Mock private MessageRepository messageRepository;

    private final HermesProperties properties = new HermesProperties();

    private CategoryService service;

    private CategoryEntity billing;
    private CategoryEntity infrastructure;
    private MessageEntity message;

    @BeforeEach
    void setUp() {
        service =
                new CategoryService(
                        categoryRepository, categoryRuleRepository, messageRepository, properties);

        billing = CategoryEntity.builder().name("Billing").color("#b8934a").build();
        infrastructure = CategoryEntity.builder().name("Infrastructure").color("#c9a227").build();
        message =
                MessageEntity.builder()
                        .provider(MailProviderType.GMAIL)
                        .externalId("id-1")
                        .sender("Hetzner <billing@hetzner.com>")
                        .subject("Rechnung")
                        .receivedAt(Instant.now())
                        .priority(Priority.NOISE)
                        .classifiedBy(ClassifiedBy.LLM)
                        .category(infrastructure)
                        .categorySource(CategorySource.LLM)
                        .categoryConfidence(0.52f)
                        .build();

        when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        when(categoryRepository.findById(billing.getId())).thenReturn(Optional.of(billing));
        when(categoryRuleRepository.findByTypeAndPattern(any(), any()))
                .thenReturn(Optional.empty());
        when(categoryRuleRepository.findByCategoryOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private AssignCategoryRequest assignTo(UUID categoryId, boolean domain, Boolean learn) {
        return new AssignCategoryRequest(message.getId(), categoryId, domain, learn);
    }

    @Test
    void aCorrectionDoesNotTouchPriority() {
        service.assign(assignTo(billing.getId(), false, null));

        assertThat(message.getCategory()).isEqualTo(billing);
        assertThat(message.getPriority()).isEqualTo(Priority.NOISE);
        assertThat(message.getClassifiedBy()).isEqualTo(ClassifiedBy.LLM);
    }

    @Test
    void aCorrectionClearsTheModelConfidenceItReplaces() {
        // The number was the classifier's, and leaving it behind would keep the message in the
        // "needs a call" queue it was just answered out of.
        service.assign(assignTo(billing.getId(), false, null));

        assertThat(message.getCategorySource()).isEqualTo(CategorySource.USER);
        assertThat(message.getCategoryConfidence()).isNull();
        assertThat(message.getCategoryCorrectedAt()).isNotNull();
    }

    @Test
    void theTrailRemembersWhatTheClassifierSaid() {
        service.assign(assignTo(billing.getId(), false, null));
        // A second correction must not overwrite the trail with the user's own first answer.
        when(categoryRepository.findById(infrastructure.getId()))
                .thenReturn(Optional.of(infrastructure));
        service.assign(assignTo(infrastructure.getId(), false, null));

        assertThat(message.getCategoryPrevious()).isEqualTo(infrastructure);
    }

    @Test
    void learningWritesASenderRuleForTheExactAddress() {
        service.assign(assignTo(billing.getId(), false, null));

        ArgumentCaptor<CategoryRuleEntity> saved =
                ArgumentCaptor.forClass(CategoryRuleEntity.class);
        verify(categoryRuleRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(RuleType.SENDER);
        assertThat(saved.getValue().getPattern()).isEqualTo("billing@hetzner.com");
        assertThat(saved.getValue().getCategory()).isEqualTo(billing);
    }

    @Test
    void renamingABuiltinIsAllowedEvenThoughDeletingItIsNot() {
        // Deleting one shrinks what the classifier may answer; renaming only changes the label.
        CategoryEntity builtin =
                CategoryEntity.builder().name("People").color("#7ba05b").builtin(true).build();
        when(categoryRepository.findById(builtin.getId())).thenReturn(Optional.of(builtin));
        when(categoryRepository.findByNameIgnoreCase("Humans")).thenReturn(Optional.empty());

        service.rename(builtin.getId(), new UpdateCategoryRequest("Humans", null));

        assertThat(builtin.getName()).isEqualTo("Humans");
        assertThat(builtin.getColor()).isEqualTo("#7ba05b");
    }

    @Test
    void renamingLeavesOmittedFieldsAlone() {
        when(categoryRepository.findById(billing.getId())).thenReturn(Optional.of(billing));

        service.rename(billing.getId(), new UpdateCategoryRequest(null, "#6b8fa8"));

        assertThat(billing.getName()).isEqualTo("Billing");
        assertThat(billing.getColor()).isEqualTo("#6b8fa8");
    }

    @Test
    void renamingToAnotherCategorysNameIsRejected() {
        when(categoryRepository.findById(billing.getId())).thenReturn(Optional.of(billing));
        when(categoryRepository.findByNameIgnoreCase("Infrastructure"))
                .thenReturn(Optional.of(infrastructure));

        assertThatThrownBy(
                        () ->
                                service.rename(
                                        billing.getId(),
                                        new UpdateCategoryRequest("Infrastructure", null)))
                .isInstanceOf(DuplicateCategoryException.class);
        assertThat(billing.getName()).isEqualTo("Billing");
    }

    @Test
    void renamingACategoryToItsOwnNameIsNotADuplicate() {
        // Recolouring without changing the name sends both fields; that must not trip the check.
        when(categoryRepository.findById(billing.getId())).thenReturn(Optional.of(billing));
        when(categoryRepository.findByNameIgnoreCase("Billing")).thenReturn(Optional.of(billing));

        service.rename(billing.getId(), new UpdateCategoryRequest("Billing", "#111111"));

        assertThat(billing.getColor()).isEqualTo("#111111");
    }

    @Test
    void deletingARuleLeavesTheMailItAlreadyFiledAlone() {
        // The rule explains how a message got here, not where it belongs.
        CategoryRuleEntity rule =
                CategoryRuleEntity.builder()
                        .category(billing)
                        .type(RuleType.SENDER)
                        .pattern("billing@hetzner.com")
                        .build();
        when(categoryRuleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        service.deleteRule(rule.getId());

        verify(categoryRuleRepository).delete(rule);
        verify(messageRepository, never()).findByCategory(any());
    }

    @Test
    void deletingAnAbsentRuleIsA404() {
        UUID missing = UUID.randomUUID();
        when(categoryRuleRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRule(missing))
                .isInstanceOf(CategoryRuleNotFoundException.class);
    }

    @Test
    void confirmingTheGuessRecordsNoChange() {
        // The user agreeing with the classifier is an action worth keeping — it writes the rule —
        // but it is not a change, and a trail saying "X (was X)" reads as a defect.
        when(categoryRepository.findById(infrastructure.getId()))
                .thenReturn(Optional.of(infrastructure));

        service.assign(assignTo(infrastructure.getId(), false, null));

        assertThat(message.getCategory()).isEqualTo(infrastructure);
        assertThat(message.getCategorySource()).isEqualTo(CategorySource.USER);
        assertThat(message.getCategoryPrevious()).isNull();
    }

    @Test
    void learningCanBeSwitchedOffForAOneOff() {
        service.assign(assignTo(billing.getId(), false, false));

        verify(categoryRuleRepository, never()).save(any());
        assertThat(message.getCategory()).isEqualTo(billing);
    }
}
