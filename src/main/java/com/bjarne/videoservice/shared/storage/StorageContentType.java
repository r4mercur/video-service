package com.bjarne.videoservice.shared.storage;

import org.springframework.stereotype.Component;

/**
 * Maps a storage key to its {@code Content-Type}. Extracted from ArtifactStorage once
 * {@code StoragePrefixMover} needed the same mapping: a visibility migration copies with
 * {@code MetadataDirective.REPLACE} (it has to, or a moved object keeps the cache policy of the
 * prefix it came from - see {@link CachePolicy}), and REPLACE drops *all* source metadata, so the
 * content type has to be supplied again rather than inherited.
 *
 * Getting this wrong is quiet and severe: an .m4s served as application/octet-stream still
 * downloads fine, so nothing errors server-side, but hls.js hands it to MSE and playback fails.
 */
@Component
public class StorageContentType {

    public String forKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Storage key must not be null");
        }
        if (key.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (key.endsWith(".m4s") || key.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (key.endsWith(".jpg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
