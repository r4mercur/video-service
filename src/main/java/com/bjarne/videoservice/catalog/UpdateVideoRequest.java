package com.bjarne.videoservice.catalog;

import jakarta.validation.constraints.Size;

/**
 * Echte PATCH-Semantik: nur nicht-null Felder werden angewendet (siehe VideoManagementService).
 */
public record UpdateVideoRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 5000) String description,
        Long categoryId,
        Visibility visibility) {
}
