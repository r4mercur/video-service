package com.bjarne.videoservice.catalog;

public record AdminCategoryDto(Long id, String slug, String name, int sortOrder, boolean active) {

    public static AdminCategoryDto from(Category category) {
        return new AdminCategoryDto(category.getId(), category.getSlug(), category.getName(),
                category.getSortOrder(), category.isActive());
    }
}
