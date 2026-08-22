package com.bjarne.videoservice.shared;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor) {
}
