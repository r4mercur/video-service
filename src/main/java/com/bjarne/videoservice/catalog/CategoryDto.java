package com.bjarne.videoservice.catalog;

public record CategoryDto(Long id, String slug, String name, int sortOrder, boolean ageRestricted) {

    public static CategoryDto from(Category category) {
        return new CategoryDto(category.getId(), category.getSlug(), category.getName(), category.getSortOrder(),
                category.isAgeRestricted());
    }
}
