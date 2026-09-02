package com.bjarne.videoservice.catalog.web;

import com.bjarne.videoservice.catalog.entity.*;
import com.bjarne.videoservice.catalog.repository.CategoryRepository;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.catalog.repository.VideoViewStatsRepository;
import com.bjarne.videoservice.identity.dto.LoginRequest;
import com.bjarne.videoservice.identity.dto.RegisterRequest;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ViewCountControllerIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private VideoViewStatsRepository viewStatsRepository;

    @Test
    void firstViewFromAnIpCountsSubsequentFromSameIpAreDeduped() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/videos/" + video.getId() + "/view").header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/videos/" + video.getId() + "/view").header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isNoContent());

        assertThat(views(video.getId())).isEqualTo(1L);
    }

    @Test
    void viewsFromDifferentIpsBothCount() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/videos/" + video.getId() + "/view").header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/videos/" + video.getId() + "/view").header("X-Forwarded-For", "10.0.0.2"))
                .andExpect(status().isNoContent());

        assertThat(views(video.getId())).isEqualTo(2L);
    }

    @Test
    void viewOnPrivateVideoByForeignUserReturnsNotFoundAndDoesNotCount() throws Exception {
        Video video = seedReadyVideo(saveUser(), Visibility.PRIVATE);
        String strangerToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + video.getId() + "/view")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
                        .header("X-Forwarded-For", "10.0.0.3"))
                .andExpect(status().isNotFound());

        assertThat(viewStatsRepository.findById(new VideoViewStatsId(video.getId(), LocalDate.now()))).isEmpty();
    }

    @Test
    void viewOnUnknownVideoReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/videos/" + UUID.randomUUID() + "/view").header("X-Forwarded-For", "10.0.0.4"))
                .andExpect(status().isNotFound());
    }

    private long views(UUID videoId) {
        return viewStatsRepository.findById(new VideoViewStatsId(videoId, LocalDate.now())).orElseThrow().getViews();
    }

    private Video seedReadyVideo(User owner, Visibility visibility) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "View Test", "view-" + UUID.randomUUID(), visibility);
        video.setStatus(VideoStatus.READY);
        video.setPublishedAt(Instant.now());
        return videoRepository.save(video);
    }

    private User saveUser() {
        return userRepository.save(new User("view-" + UUID.randomUUID() + "@example.com",
                "view-" + UUID.randomUUID(), "irrelevant-hash"));
    }

    private String registerAndLogin() throws Exception {
        String email = "view-test-" + UUID.randomUUID() + "@example.com";
        String username = "view-test-" + UUID.randomUUID();
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
