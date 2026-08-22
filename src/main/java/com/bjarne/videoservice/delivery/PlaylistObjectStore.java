package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.config.DeliveryProperties;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Cached die rohen Playlist-Bytes (master.m3u8 / Rendition-playlist.m3u8) pro Storage-Key, um bei
 * mehreren Zuschauern desselben Videos nicht bei jedem Request erneut zum Storage zu muessen.
 * Bewusst NUR die rohen Bytes - presignte Segment-URLs (siehe SegmentPresigner) werden bei jedem
 * Request frisch erzeugt, da das Presignen eine reine lokale HMAC-Berechnung ohne Netzwerk-Call
 * ist und ein Cache dort keinen Vorteil brächte, aber staerkeres TTL-Handling noetig machen wuerde.
 * In-Process-Cache (kein Redis) ist ausreichend, solange die API-Rolle nicht horizontal skaliert
 * wird (siehe CLAUDE.md 3.1/11).
 */
@Component
public class PlaylistObjectStore {

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final S3BucketInitializer bucketInitializer;
    private final Cache<String, String> cache;

    public PlaylistObjectStore(S3Client s3Client, S3Properties s3Properties, S3BucketInitializer bucketInitializer,
                                DeliveryProperties deliveryProperties) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
        this.bucketInitializer = bucketInitializer;
        this.cache = Caffeine.newBuilder()
                .maximumSize(deliveryProperties.playlistCacheMaxSize())
                .expireAfterWrite(deliveryProperties.playlistCacheTtl())
                .build();
    }

    public String fetch(String key) {
        return cache.get(key, this::download);
    }

    private String download(String key) {
        bucketInitializer.ensureReady();
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(key)
                .build());
        return object.asUtf8String();
    }
}
