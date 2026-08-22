package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.catalog.Visibility;
import org.springframework.stereotype.Component;

/**
 * Loest einen rohen Storage-Key (Thumbnail, Sprite-Sheet) in eine fuer den Client tatsaechlich
 * abrufbare URL auf - genau eine Stelle (CLAUDE.md 3.2), genutzt von {@link com.bjarne.videoservice.catalog.CatalogService}.
 * PUBLIC-Keys beginnen bereits mit "public/..." (siehe UploadService) und werden ueber den
 * Caddy-Passthrough ausgeliefert, PRIVATE-Keys brauchen eine presignte URL (CLAUDE.md 9.3).
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
