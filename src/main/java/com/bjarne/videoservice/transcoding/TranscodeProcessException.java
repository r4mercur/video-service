package com.bjarne.videoservice.transcoding;

/**
 * Prozess-/Infrastrukturfehler (Timeout, Exit-Code != 0, IO-Fehler). Retryable ueber
 * attempts/max_attempts mit Backoff - im Gegensatz zu {@link MediaValidationException}.
 */
public class TranscodeProcessException extends RuntimeException {

    public TranscodeProcessException(String message) {
        super(message);
    }

    public TranscodeProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
