package com.bjarne.videoservice.delivery.storage;

import com.bjarne.videoservice.config.DeliveryProperties;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Caches the raw playlist bytes (master.m3u8 / rendition playlist.m3u8) per storage key, so that
 * with multiple viewers of the same video we don't have to hit storage again on every request.
 * Deliberately ONLY the raw bytes - presigned segment URLs (see SegmentPresigner) are freshly
 * generated on every request, since presigning is a pure local HMAC computation without a
 * network call, so a cache there would bring no benefit but would require stricter TTL handling.
 * An in-process cache (no Redis) is sufficient as long as the API role isn't scaled horizontally
 * (see CLAUDE.md 3.1/11).
 */
@Component
public class PlaylistObjectStore {

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final S3BucketInitializer bucketInitializer;
    private final Cache<String, String> cache;

    public PlaylistObjectStore(S3Client s3Client, S3Properties s3Properties, S3BucketInitializer bucketInitializer,
                                DeliveryProperties deliveryProperties, MeterRegistry meterRegistry) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
        this.bucketInitializer = bucketInitializer;
        this.cache = Caffeine.newBuilder()
                .maximumSize(deliveryProperties.playlistCacheMaxSize())
                .expireAfterWrite(deliveryProperties.playlistCacheTtl())
                // recordStats is required for CaffeineCacheMetrics to report anything but zeros.
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "playlists");
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
