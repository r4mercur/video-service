package com.bjarne.videoservice.shared;

import java.util.List;

/**
 * Numbered page (1-based) for the endpoints that are allowed OFFSET paging - currently only
 * search (CLAUDE.md 3.2). Everything else in the catalog uses {@link CursorPage}.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
