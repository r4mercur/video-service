package com.bjarne.videoservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates the bucket + CORS lazily on the first actual S3 access instead of eagerly at app
 * startup: an ApplicationRunner would couple every Spring context (including tests without an
 * S3 connection) to storage availability. Used from multiple places (upload.S3MultipartClient,
 * transcoding.ArtifactStorage) - hence centralized here instead of duplicated.
 */
@Component
public class S3BucketInitializer {

    private static final Logger log = LoggerFactory.getLogger(S3BucketInitializer.class);

    private final S3Client s3Client;
    private final S3Properties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public S3BucketInitializer(S3Client s3Client, S3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    public void ensureReady() {
        if (!bucketReady.compareAndSet(false, true)) {
            return;
        }
        try {
            ensureBucketExists();
            ensureBucketCors();
            ensureBucketPublicReadPolicy();
        } catch (RuntimeException e) {
            bucketReady.set(false);
            throw e;
        }
    }

    private void ensureBucketExists() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("Bucket '{}' created", properties.bucket());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("Bucket '{}' already exists", properties.bucket());
        }
    }

    /**
     * MinIO does not implement the classic S3 CORS API (PutBucketCors) and responds with
     * HTTP 501 - CORS must instead be set there server-side via MINIO_API_CORS_ALLOW_ORIGIN
     * (see compose.yaml). On backends that do support the API (Garage, real AWS S3), this call
     * applies normally. Other errors are not swallowed.
     */
    private void ensureBucketCors() {
        CORSRule rule = CORSRule.builder()
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedOrigins(properties.corsAllowedOrigins())
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
        try {
            s3Client.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(properties.bucket())
                    .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 501) {
                log.warn("Storage backend does not support the S3 CORS API (HTTP 501) - CORS must "
                        + "be configured server-side (for MinIO e.g. MINIO_API_CORS_ALLOW_ORIGIN)");
            } else {
                throw e;
            }
        }
    }

    /**
     * The "public/" prefix is passed through anonymously by Caddy (AP6, no auth header) -
     * without a bucket policy for exactly this prefix, a backend that supports one responds
     * with 403 instead of serving the object. "private/" is deliberately excluded, there
     * objects are only ever delivered via presigned URLs with an expiry (see the delivery
     * package, AP6/9.3).
     *
     * Garage does not implement this API at all (verified against v1.0.1: PutBucketPolicy
     * always returns 501, not just "not yet"). It has no bucket-policy concept - public reads
     * there are instead enabled once via its own website config (the garage-init bootstrap in
     * compose.yaml, see garage/garage.toml), which Caddy's @publicAssets block targets
     * directly. Hetzner Object Storage in production supports real prefix-scoped bucket
     * policies, so this call does its actual job there.
     */
    private void ensureBucketPublicReadPolicy() {
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/public/*"]
                    }
                  ]
                }
                """.formatted(properties.bucket());
        try {
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(properties.bucket())
                    .policy(policy)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 501) {
                log.warn("Storage backend does not support the S3 bucket-policy API (HTTP 501) - "
                        + "public read access must be configured out of band (for Garage, via its "
                        + "own website config)");
            } else {
                throw e;
            }
        }
    }
}
