package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.identity.service.JwtService;
import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container-level multipart limit (spring.servlet.multipart.max-file-size) only exists in a
 * real servlet container - MockMvc skips multipart parsing entirely. Hence a real port and a real
 * HTTP request here. Guards the regression where Spring's 1 MB default undercut every per-feature
 * upload cap and the resulting exception surfaced as a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartLimitIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BOUNDARY = "multipart-limit-test";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    /**
     * 8.5 MB: above max-file-size (8 MB) but below max-request-size (9 MB), so Tomcat reads the
     * part until the file limit trips and can swallow the small remainder - the client gets the
     * ProblemDetail. A request above max-request-size is refused before its body is read, and
     * once the unread rest exceeds Tomcat's max-swallow-size (2 MB) the connection is simply
     * closed: the browser sees a network error, not a 413. That is why the frontend has to check
     * the file size before uploading - this handler is only the backstop.
     */
    @Test
    void uploadAboveTheFileLimitIsRejectedWith413() throws Exception {
        HttpResponse<String> response = putAvatar(new byte[8 * 1024 * 1024 + 512 * 1024]);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("too large");
    }

    /**
     * A 2 MB file is above Spring's old 1 MB default but below every per-feature cap - it has to
     * reach the service. An all-zero file is not an image, so the service answers 400 - which is
     * exactly the proof that the container let it through.
     */
    @Test
    void uploadBetweenTheOldDefaultAndTheAvatarCapReachesTheService() throws Exception {
        HttpResponse<String> response = putAvatar(new byte[2 * 1024 * 1024]);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> putAvatar(byte[] content) throws Exception {
        User user = userRepository.save(new User("multipart-" + UUID.randomUUID() + "@example.com",
                "multipart-" + UUID.randomUUID(), "irrelevant-hash"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/avatar"))
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(user))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(multipartBody(content)))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static byte[] multipartBody(byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"me.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }
}
