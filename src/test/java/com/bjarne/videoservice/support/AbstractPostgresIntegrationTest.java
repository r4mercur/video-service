package com.bjarne.videoservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    // Root cause of the 2026-08-28 "Connection refused" flakiness across the whole suite: this
    // container is meant to be a JVM-wide singleton shared by every subclass, but @Container +
    // @Testcontainers manage lifecycle PER TEST CLASS - each subclass's own beforeAll/afterAll
    // called start()/stop() on this SAME static field, so class A's afterAll would stop the
    // container out from under whichever class ran next, which then created (and later stopped)
    // its own replacement, and so on - dozens of Postgres/MinIO containers churning through a
    // single suite run instead of one shared instance, until Docker or the JVM ran out of room.
    // This is a documented Testcontainers gotcha (see java.testcontainers.org's JUnit 5 guide):
    // the fix for a container shared across sibling test classes via an abstract base is a plain
    // static initializer, NOT @Container - that way no single subclass's extension ever believes
    // it owns (and may tear down) the shared instance; start() runs exactly once, guaranteed by
    // normal JVM class-initialization semantics, and nothing ever calls stop() on it.
    // @ServiceConnection still works from here - it reads connection details from the field's
    // current (running) state at Spring context-refresh time and doesn't care how the container's
    // own lifecycle is managed.
    //
    // The explicit memory cap is unrelated but kept as a separate safety net: without one, an
    // actual OOM kill of this container shows up as Docker's OOMKilled=false (that flag only
    // fires for a container's own cgroup limit) and the kernel's system-wide OOM killer picks a
    // victim instead, which is indistinguishable from the outside from a crash. 512MB is generous
    // for this workload (a handful of MockMvc-driven integration tests, not a production
    // instance) - if this cap is ever actually hit, Docker will report OOMKilled=true against
    // this specific container instead of an opaque "Connection refused".
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

    static {
        POSTGRES.start();
    }

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
