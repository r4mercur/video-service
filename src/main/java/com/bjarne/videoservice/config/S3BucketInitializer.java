package com.bjarne.videoservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Legt Bucket + CORS lazy beim ersten tatsaechlichen S3-Zugriff an statt eager beim App-Start:
 * ein ApplicationRunner haette jeden Spring-Context (auch Tests ohne S3-Anbindung) an die
 * Storage-Verfuegbarkeit gekoppelt. Von mehreren Stellen genutzt (upload.S3MultipartClient,
 * transcoding.ArtifactStorage) - daher hier zentral statt dupliziert.
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
        } catch (RuntimeException e) {
            bucketReady.set(false);
            throw e;
        }
    }

    private void ensureBucketExists() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("Bucket '{}' angelegt", properties.bucket());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("Bucket '{}' existiert bereits", properties.bucket());
        }
    }

    /**
     * MinIO implementiert die klassische S3-CORS-API (PutBucketCors) nicht und antwortet mit
     * HTTP 501 - CORS muss dort stattdessen serverseitig via MINIO_API_CORS_ALLOW_ORIGIN gesetzt
     * werden (siehe compose.yaml). Auf Backends, die die API unterstuetzen (Garage, echtes AWS S3),
     * greift dieser Aufruf regulaer. Andere Fehler werden nicht verschluckt.
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
                log.warn("Storage-Backend unterstuetzt die S3-CORS-API nicht (HTTP 501) - CORS muss "
                        + "serverseitig konfiguriert werden (bei MinIO z.B. MINIO_API_CORS_ALLOW_ORIGIN)");
            } else {
                throw e;
            }
        }
    }
}
