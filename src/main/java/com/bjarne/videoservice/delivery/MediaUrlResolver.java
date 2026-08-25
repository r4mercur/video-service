package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.catalog.Visibility;
import org.springframework.stereotype.Component;

/**
 * Resolves a raw storage key (thumbnail, sprite sheet) into a URL the client can actually
 * fetch - exactly one place (CLAUDE.md 3.2), used by {@link com.bjarne.videoservice.catalog.CatalogService}.
 * PUBLIC keys already start with "public/..." (see UploadService) and are delivered via the
 * Caddy passthrough, PRIVATE keys need a presigned URL (CLAUDE.md 9.3).
 */
@Component
public class MediaUrlResolver {

    private final ObjectPresigner presigner;

    public MediaUrlResolver(ObjectPresigner presigner) {
        this.presigner = presigner;
    }

    public String resolve(Visibility visibility, String key) {
        if (key == null) {
            return null;
        }
        return visibility == Visibility.PUBLIC ? "/" + key : presigner.presignGet(key);
    }
}
