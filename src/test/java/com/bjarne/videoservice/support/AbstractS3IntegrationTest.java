package com.bjarne.videoservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deliberately no @ServiceConnection - Spring Boot 4 has no built-in service connection
 * for a raw S3Client bean, hence explicit @DynamicPropertySource wiring.
 */
@Testcontainers
public abstract class AbstractS3IntegrationTest extends AbstractPostgresIntegrationTest {

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
            .withUserName("test-access-key")
            .withPassword("test-secret-key")
            .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "*");

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.access-key", MINIO::getUserName);
        registry.add("app.storage.secret-key", MINIO::getPassword);
        registry.add("app.storage.bucket", () -> "video-service-test");
    }
}
