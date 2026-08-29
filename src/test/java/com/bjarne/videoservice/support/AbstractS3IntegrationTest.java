package com.bjarne.videoservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately no @ServiceConnection - Spring Boot 4 has no built-in service connection
 * for a raw S3Client bean, hence explicit @DynamicPropertySource wiring.
 */
public abstract class AbstractS3IntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ADMIN_TOKEN =
            "b38b9abe2c682063951276fa0d2da8048b1b93cabe622fe45936287f296dbffa";
    private static final String KEY_ID = "GK11286dbe2827cfc485299645";
    private static final String KEY_SECRET =
            "1c11b411b83e8c65c3a76e7c4cc7ef32022f0c8f5d2571ae6c4510a02a908fff";
    private static final String BUCKET = "video-service-test";

    // Static initializer, not @Container - same reasoning as POSTGRES in
    // AbstractPostgresIntegrationTest (a container shared across sibling subclasses via an
    // abstract base must not be lifecycle-managed by any single subclass's @Testcontainers
    // extension, or that class's afterAll stops it out from under the next class).
    // Explicit memory cap - same rationale as POSTGRES.
    //
    // No dedicated Testcontainers module exists for Garage (unlike the old MinIOContainer) -
    // it's a plain GenericContainer, bootstrapped the same way as the compose.yaml dev stack
    // (garage/init.sh): Garage has no S3-API-level way to create its own access key or enable
    // public website access, so both go through its HTTP admin API (port 3903) via a plain
    // HttpClient in bootstrap() below - the garage image is a bare static binary with no
    // shell, so a wrapper script inside the container isn't an option here either.
    static final GenericContainer<?> GARAGE = new GenericContainer<>("dxflrs/garage:v1.0.1")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024))
            .withEnv("GARAGE_ALLOW_WORLD_READABLE_SECRETS", "true")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("garage/garage.toml"), "/etc/garage.toml")
            .withExposedPorts(3900, 3903)
            .waitingFor(Wait.forHttp("/v1/status")
                    .forPort(3903)
                    .withHeader("Authorization", "Bearer " + ADMIN_TOKEN)
                    .forStatusCode(200));

    static {
        GARAGE.start();
        bootstrap();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", () ->
                "http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3900));
        // Must match garage.toml's s3_region exactly - Garage rejects a request signed with
        // any other region string (AuthorizationHeaderMalformed), unlike MinIO/AWS.
        registry.add("app.storage.region", () -> "garage");
        registry.add("app.storage.access-key", () -> KEY_ID);
        registry.add("app.storage.secret-key", () -> KEY_SECRET);
        registry.add("app.storage.bucket", () -> BUCKET);
    }

    /**
     * Mirrors garage/init.sh's bootstrap (layout, key, bucket, permissions) - see that
     * script's header comment for why the admin API is used instead of the garage CLI.
     */
    private static void bootstrap() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String admin = "http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3903);

            String status = call(client, admin + "/v1/status", "GET", null, false);
            String nodeId = extract(status, "\"node\"\\s*:\\s*\"([^\"]+)\"");

            String layout = call(client, admin + "/v1/layout", "GET", null, false);
            if ("0".equals(extract(layout, "\"version\"\\s*:\\s*(\\d+)"))) {
                call(client, admin + "/v1/layout", "POST",
                        "[{\"id\": \"%s\", \"zone\": \"dc1\", \"capacity\": 1000000000, \"tags\": []}]"
                                .formatted(nodeId),
                        false);
                call(client, admin + "/v1/layout/apply", "POST", "{\"version\": 1}", false);
            }

            call(client, admin + "/v1/key/import", "POST",
                    "{\"accessKeyId\": \"%s\", \"secretAccessKey\": \"%s\", \"name\": \"video-service-test\"}"
                            .formatted(KEY_ID, KEY_SECRET),
                    true);

            String bucketResponse = call(client, admin + "/v1/bucket", "POST",
                    "{\"globalAlias\": \"%s\"}".formatted(BUCKET), true);
            String bucketId = extract(bucketResponse, "\"id\"\\s*:\\s*\"([^\"]+)\"");
            if (bucketId == null) {
                bucketId = extract(
                        call(client, admin + "/v1/bucket?globalAlias=" + BUCKET, "GET", null, false),
                        "\"id\"\\s*:\\s*\"([^\"]+)\"");
            }

            call(client, admin + "/v1/bucket/allow", "POST",
                    "{\"bucketId\": \"%s\", \"accessKeyId\": \"%s\", \"permissions\": {\"read\": true, \"write\": true, \"owner\": true}}"
                            .formatted(bucketId, KEY_ID),
                    false);
        } catch (Exception e) {
            throw new IllegalStateException("Garage test container bootstrap failed", e);
        }
    }

    // allowConflict swallows HTTP 409 (already exists) - relevant only if this static
    // initializer is ever re-entered; normal JVM class-init semantics guarantee it isn't,
    // but the tolerance costs nothing and matches garage/init.sh's own reasoning for reuse.
    private static String call(HttpClient client, String url, String method, String body,
            boolean allowConflict) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("Content-Type", "application/json");
        builder = body == null ? builder.GET() : builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || (allowConflict && response.statusCode() == 409)) {
            return response.statusCode() == 200 ? response.body() : null;
        }
        throw new IllegalStateException(
                "Garage admin API call to " + url + " failed: " + response.statusCode() + " " + response.body());
    }

    private static String extract(String json, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
