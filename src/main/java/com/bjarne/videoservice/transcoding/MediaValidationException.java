package com.bjarne.videoservice.transcoding;

/**
 * Source is unsuitable in content (no video track, too long, implausible resolution, broken
 * container). Not retryable - a retry doesn't change anything about the file itself.
 */
public class MediaValidationException extends RuntimeException {

    public MediaValidationException(String message) {
        super(message);
    }
}
