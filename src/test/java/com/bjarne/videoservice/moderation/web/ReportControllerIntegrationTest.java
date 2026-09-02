package com.bjarne.videoservice.moderation.web;

import com.bjarne.videoservice.catalog.entity.Category;
import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.CategoryRepository;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.identity.dto.LoginRequest;
import com.bjarne.videoservice.identity.dto.RegisterRequest;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.moderation.dto.SubmitReportRequest;
import com.bjarne.videoservice.moderation.entity.ReportReason;
import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ReportControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void submitReportOnPublicVideoReturnsCreatedOpenReport() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("SPAM", "looks like spam")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void submitReportWithoutAuthReturnsCreatedOpenReport() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("SPAM", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void submitReportOnPrivateVideoByForeignUserReturnsNotFound() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PRIVATE);
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("OTHER", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitReportOnPrivateVideoWithoutAuthReturnsNotFound() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PRIVATE);

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .with(remoteAddr(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("OTHER", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitReportOnUnknownVideoReturnsNotFound() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + UUID.randomUUID() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("OTHER", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void exceedingAnonymousReportRateLimitReturnsTooManyRequests() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);
        String ip = uniqueIp();

        // Default limit: 3/hour (app.rate-limit.report-anonymous) - the 4th request must return 429.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                            .with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reportJson("SPAM", "attempt " + i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("SPAM", "one too many")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void exceedingReportRateLimitReturnsTooManyRequests() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);
        String accessToken = registerAndLogin();

        // Default limit: 10/hour (app.rate-limit.report) - the 11th request must return 429.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reportJson("SPAM", "attempt " + i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("SPAM", "one too many")))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * MockMvc requests otherwise all share the same client IP, which would make the
     * anonymous-report rate-limit bucket (a singleton cache, not reset between tests) bleed
     * across test methods. Each test that submits an anonymous report gets its own synthetic IP.
     */
    private static RequestPostProcessor remoteAddr(String ip) {
        return (MockHttpServletRequest request) -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger(1);

    private static String uniqueIp() {
        return "10.0.0." + IP_SEQUENCE.getAndIncrement();
    }

    private String reportJson(String reason, String detail) throws Exception {
        return objectMapper.writeValueAsString(new SubmitReportRequest(ReportReason.valueOf(reason), detail));
    }

    private Video seedReadyVideo(User owner, Visibility visibility) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Report Test", "report-" + UUID.randomUUID(), visibility);
        video.setStatus(VideoStatus.READY);
        video.setPublishedAt(Instant.now());
        return videoRepository.save(video);
    }

    private User saveUser() {
        return userRepository.save(new User("report-owner-" + UUID.randomUUID() + "@example.com",
                "report-owner-" + UUID.randomUUID(), "irrelevant-hash"));
    }

    private String registerAndLogin() throws Exception {
        String email = "report-test-" + UUID.randomUUID() + "@example.com";
        String username = "report-test-" + UUID.randomUUID();
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, username, "password123"))))
                .andExpect(status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");
    }
}
