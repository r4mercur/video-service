package com.bjarne.videoservice.catalog.dto;

import jakarta.validation.constraints.Size;

/**
 * Slug is deliberately not editable (avoids breaking external links/category filter URLs).
 * True PATCH semantics: only non-null fields are applied.
 */
public record UpdateCategoryRequest(
        @Size(max = 100)
        String name,
        Integer sortOrder,
        Boolean active,
        Boolean ageRestricted) {
}
