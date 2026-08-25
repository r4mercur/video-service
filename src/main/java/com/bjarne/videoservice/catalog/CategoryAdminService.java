package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryAdminService {

    private final CategoryRepository categoryRepository;

    public CategoryAdminService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> listAll() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream().map(AdminCategoryDto::from).toList();
    }

    @Transactional
    public AdminCategoryDto create(CreateCategoryRequest request) {
        if (categoryRepository.findBySlug(request.slug()).isPresent()) {
            throw new ConflictException("Slug already in use: " + request.slug());
        }
        Category category = new Category(request.slug(), request.name(), request.sortOrder());
        categoryRepository.save(category);
        return AdminCategoryDto.from(category);
    }

    @Transactional
    public AdminCategoryDto update(Long id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found"));
        if (request.name() != null) {
            category.setName(request.name());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        categoryRepository.save(category);
        return AdminCategoryDto.from(category);
    }
}
