package com.bjarne.videoservice.catalog;

public record CategoryDto(Long id, String slug, String name, int sortOrder) {

    public static CategoryDto from(Category category) {
        return new CategoryDto(category.getId(), category.getSlug(), category.getName(), category.getSortOrder());
    }
}
