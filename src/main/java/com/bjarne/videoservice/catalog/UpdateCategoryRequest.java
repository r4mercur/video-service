package com.bjarne.videoservice.catalog;

import jakarta.validation.constraints.Size;

/**
 * Slug ist absichtlich nicht aenderbar (vermeidet gebrochene externe Links/Kategorie-Filter-URLs).
 * Echte PATCH-Semantik: nur nicht-null Felder werden angewendet.
 */
public record UpdateCategoryRequest(@Size(max = 100) String name, Integer sortOrder, Boolean active) {
}
