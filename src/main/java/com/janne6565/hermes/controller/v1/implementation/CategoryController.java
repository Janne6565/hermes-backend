package com.janne6565.hermes.controller.v1.implementation;

import com.janne6565.hermes.configuration.HermesProperties;
import com.janne6565.hermes.controller.v1.schema.CategoryApi;
import com.janne6565.hermes.model.action.AssignCategoryRequest;
import com.janne6565.hermes.model.action.CreateCategoryRequest;
import com.janne6565.hermes.model.core.CategoryDto;
import com.janne6565.hermes.model.core.CategoryOverviewDto;
import com.janne6565.hermes.services.categories.CategoryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CategoryController implements CategoryApi {

    private final CategoryService categoryService;
    private final HermesProperties properties;

    @Override
    public ResponseEntity<CategoryOverviewDto> overview(Integer days) {
        int window = days != null ? days : properties.getCategories().getWindowDays();
        return ResponseEntity.ok(categoryService.overview(window));
    }

    @Override
    public ResponseEntity<CategoryDto> create(CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CategoryDto> assign(AssignCategoryRequest request) {
        return ResponseEntity.ok(categoryService.assign(request));
    }
}
