package com.bjarne.videoservice.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "[a-z0-9-]+", message = "slug must be lowercase alphanumeric with hyphens") String slug,
        @NotBlank @Size(max = 100) String name,
        int sortOrder) {
}
