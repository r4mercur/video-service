package com.bjarne.videoservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

/**
 * Deliberately no @ServiceConnection - Spring Boot 4 has no built-in service connection
 * for a raw S3Client bean, hence explicit @DynamicPropertySource wiring.
 */
public abstract class AbstractS3IntegrationTest extends AbstractPostgresIntegrationTest {

    // Static initializer, not @Container - same reasoning as POSTGRES in
    // AbstractPostgresIntegrationTest (a container shared across sibling subclasses via an
    // abstract base must not be lifecycle-managed by any single subclass's @Testcontainers
    // extension, or that class's afterAll stops it out from under the next class).
    // Explicit memory cap - same rationale as POSTGRES.
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
            .withUserName("test-access-key")
            .withPassword("test-secret-key")
            .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "*")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.access-key", MINIO::getUserName);
        registry.add("app.storage.secret-key", MINIO::getPassword);
        registry.add("app.storage.bucket", () -> "video-service-test");
    }
}
