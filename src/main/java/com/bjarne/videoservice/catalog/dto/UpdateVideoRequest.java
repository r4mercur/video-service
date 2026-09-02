package com.bjarne.videoservice.catalog.dto;

import com.bjarne.videoservice.catalog.entity.Visibility;
import jakarta.validation.constraints.Size;

/**
 * True PATCH semantics: only non-null fields are applied (see VideoManagementService).
 */
public record UpdateVideoRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 5000) String description,
        Long categoryId,
        Visibility visibility) {
}
