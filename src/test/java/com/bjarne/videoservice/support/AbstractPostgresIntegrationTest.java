package com.bjarne.videoservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /*
     * The Spring context (incl. the RateLimiter singleton) is cached/shared across many test
     * classes - with the strict production limits, registerAndLogin() from unrelated tests
     * would eventually hit a spurious 429. app.rate-limit.report is deliberately left
     * unchanged (ReportControllerIntegrationTest tests exactly its default).
     */
    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.login.capacity", () -> "100000");
        registry.add("app.rate-limit.login.refill-period", () -> "PT1S");
        registry.add("app.rate-limit.register.capacity", () -> "100000");
        registry.add("app.rate-limit.register.refill-period", () -> "PT1S");
    }
}
