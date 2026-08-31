package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.catalog.Visibility;
import com.bjarne.videoservice.config.S3Properties;
import org.springframework.stereotype.Component;

/**
 * Resolves a raw storage key (thumbnail, sprite sheet, playlist) into a URL the client can
 * actually fetch - exactly one place (CLAUDE.md 3.2), used by
 * {@link com.bjarne.videoservice.catalog.CatalogService} and {@link PlaylistService}.
 * PUBLIC keys already start with "public/..." (see UploadService) and are served directly from
 * object storage at {@code publicBaseUrl} (CLAUDE.md 9.3 - Caddy is deliberately not in the
 * media data path in production), PRIVATE keys need a presigned URL.
 *
 * A PUBLIC key used to resolve to a bare "/" + key (no host) instead - that happens to still
 * work in dev only because the dev Caddyfile proxies /public/* to Garage itself (Garage has no
 * bucket-policy API), which production's Caddyfile intentionally does not do. In production that
 * relative path fell through Caddy's SPA catch-all (try_files ... /index.html) and returned the
 * Angular shell with a 200 instead of the actual manifest/thumbnail - playback failed with no
 * server-side error to point at. Found 2026-08-31 chasing an unrelated upload report.
 */
@Component
public class MediaUrlResolver {

    private final ObjectPresigner presigner;
    private final String publicBaseUrl;

    public MediaUrlResolver(ObjectPresigner presigner, S3Properties properties) {
        this.presigner = presigner;
        this.publicBaseUrl = properties.publicBaseUrl();
    }

    public String resolve(Visibility visibility, String key) {
        if (key == null) {
            return null;
        }
        return visibility == Visibility.PUBLIC ? publicBaseUrl + "/" + key : presigner.presignGet(key);
    }
}
