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
     * Der Spring-Context (inkl. RateLimiter-Singleton) wird ueber viele Testklassen hinweg
     * gecacht/geteilt - mit den scharfen Produktions-Limits wuerde registerAndLogin() aus
     * unrelated Tests irgendwann in ein spurious 429 laufen. app.rate-limit.report bleibt
     * bewusst unveraendert (ReportControllerIntegrationTest prueft genau dessen Default).
     */
    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.login.capacity", () -> "100000");
        registry.add("app.rate-limit.login.refill-period", () -> "PT1S");
        registry.add("app.rate-limit.register.capacity", () -> "100000");
        registry.add("app.rate-limit.register.refill-period", () -> "PT1S");
    }
}
