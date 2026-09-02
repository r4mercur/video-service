package com.bjarne.videoservice.transcoding.service;

/**
 * Process/infrastructure error (timeout, exit code != 0, IO error). Retryable via
 * attempts/max_attempts with backoff - unlike {@link MediaValidationException}.
 */
public class TranscodeProcessException extends RuntimeException {

    public TranscodeProcessException(String message) {
        super(message);
    }

    public TranscodeProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
