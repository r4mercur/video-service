package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.shared.exceptions.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        Instant timestamp = Instant.parse("2026-01-15T10:30:00Z");
        UUID id = UUID.randomUUID();

        String cursor = CursorCodec.encode(timestamp, id);
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor);

        assertThat(decoded.timestamp()).isEqualTo(timestamp);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void decodeInvalidCursorThrowsValidationException() {
        assertThatThrownBy(() -> CursorCodec.decode("not-a-valid-cursor!!"))
                .isInstanceOf(ValidationException.class);
    }
}
