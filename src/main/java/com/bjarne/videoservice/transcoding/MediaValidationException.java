package com.bjarne.videoservice.transcoding;

/**
 * Quelle ist inhaltlich ungeeignet (keine Videospur, zu lang, unplausible Aufloesung, kaputter
 * Container). Nicht retryable - ein erneuter Versuch aendert nichts an der Datei selbst.
 */
public class MediaValidationException extends RuntimeException {

    public MediaValidationException(String message) {
        super(message);
    }
}
