package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.shared.exceptions.ValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opake Keyset-Cursor fuer Cursor-Pagination (CLAUDE.md 3.2: kein OFFSET-Paging).
 * Kodiert Timestamp + Tie-Breaker-ID, unabhaengig davon auf welcher Timestamp-Spalte
 * die jeweilige Query sortiert.
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public record Cursor(Instant timestamp, UUID id) {
    }

    public static String encode(Instant timestamp, UUID id) {
        String raw = timestamp.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            Instant timestamp = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator)));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new Cursor(timestamp, id);
        } catch (RuntimeException e) {
            throw new ValidationException("Invalid cursor");
        }
    }
}
