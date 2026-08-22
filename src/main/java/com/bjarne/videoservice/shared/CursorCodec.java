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

    public record LongIdCursor(Instant timestamp, Long id) {
    }

    public static String encode(Instant timestamp, UUID id) {
        return encodeRaw(timestamp, id);
    }

    public static Cursor decode(String cursor) {
        String[] parts = decodeRaw(cursor);
        return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
    }

    /**
     * Fuer Long-PK-Entitaeten (z.B. reports) - gleiches Kodierungsschema wie {@link #encode},
     * nur mit Long statt UUID als Tie-Breaker.
     */
    public static String encodeLong(Instant timestamp, Long id) {
        return encodeRaw(timestamp, id);
    }

    public static LongIdCursor decodeLong(String cursor) {
        String[] parts = decodeRaw(cursor);
        return new LongIdCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
    }

    private static String encodeRaw(Instant timestamp, Object id) {
        String raw = timestamp.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeRaw(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            return new String[] {raw.substring(0, separator), raw.substring(separator + 1)};
        } catch (RuntimeException e) {
            throw new ValidationException("Invalid cursor");
        }
    }
}
