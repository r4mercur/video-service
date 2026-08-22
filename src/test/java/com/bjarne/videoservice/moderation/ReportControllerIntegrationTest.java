package com.bjarne.videoservice.moderation;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.identity.LoginRequest;
import com.bjarne.videoservice.identity.RegisterRequest;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
    void submitReportWithoutAuthReturnsUnauthorized() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/videos/" + video.getId() + "/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("SPAM", null)))
                .andExpect(status().isUnauthorized());
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
    void submitReportOnUnknownVideoReturnsNotFound() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + UUID.randomUUID() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("OTHER", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void exceedingReportRateLimitReturnsTooManyRequests() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);
        String accessToken = registerAndLogin();

        // Default-Limit: 10/Stunde (app.rate-limit.report) - der 11. Request muss 429 liefern.
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
