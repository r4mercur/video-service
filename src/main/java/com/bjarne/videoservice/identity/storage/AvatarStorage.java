package com.bjarne.videoservice.identity.storage;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.shared.storage.CachePolicy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Profile photo objects (CLAUDE.md 9.8). Every upload gets a fresh key under the user's own
 * prefix, never an overwrite in place - that is what makes CachePolicy's immutable rule for
 * {@link CachePolicy#AVATAR_PREFIX} correct. The per-user prefix also means a future account
 * deletion can empty {@code public/avatars/{userId}/} as a whole, including any object orphaned by
 * a failed cleanup (CLAUDE.md 12).
 */
@Component
public class AvatarStorage {

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final S3BucketInitializer bucketInitializer;
    private final CachePolicy cachePolicy;

    public AvatarStorage(S3Client s3Client, S3Properties s3Properties, S3BucketInitializer bucketInitializer,
                         CachePolicy cachePolicy) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
        this.bucketInitializer = bucketInitializer;
        this.cachePolicy = cachePolicy;
    }

    public String newKey(UUID userId) {
        return CachePolicy.AVATAR_PREFIX + userId + "/" + UUID.randomUUID() + ".jpg";
    }

    public void put(String key, Path jpeg) {
        bucketInitializer.ensureReady();
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(key)
                .contentType("image/jpeg")
                .cacheControl(cachePolicy.cacheControlFor(key))
                .build(), RequestBody.fromFile(jpeg));
    }

    public void delete(String key) {
        bucketInitializer.ensureReady();
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(key)
                .build());
    }
}
