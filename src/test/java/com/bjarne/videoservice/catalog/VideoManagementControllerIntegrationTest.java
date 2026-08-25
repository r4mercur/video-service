package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.identity.LoginRequest;
import com.bjarne.videoservice.identity.RegisterRequest;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.moderation.Report;
import com.bjarne.videoservice.moderation.ReportRepository;
import com.bjarne.videoservice.support.AbstractS3IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class VideoManagementControllerIntegrationTest extends AbstractS3IntegrationTest {

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
    private ReportRepository reportRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private S3BucketInitializer bucketInitializer;

    private String currentUsername;

    @Test
    void updateTitleDescriptionAndCategoryAppliesOnlyProvidedFields() throws Exception {
        String accessToken = registerAndLogin();
        Video video = seedVideo(currentUser(), Visibility.PUBLIC);
        Category music = categoryRepository.findBySlug("music").orElseThrow();

        mockMvc.perform(patch("/api/videos/" + video.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateVideoRequest(
                                "New Title", null, music.getId(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"))
                .andExpect(jsonPath("$.categorySlug").value("music"));

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getDescription()).isEqualTo("original description");
        assertThat(reloaded.getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void updateVisibilityMovesObjectsBetweenStoragePrefixes() throws Exception {
        String accessToken = registerAndLogin();
        Video video = seedVideo(currentUser(), Visibility.PUBLIC);
        String oldKey = video.getStoragePrefix() + "/master.m3u8";

        mockMvc.perform(patch("/api/videos/" + video.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateVideoRequest(
                                null, null, null, Visibility.PRIVATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        String newPrefix = "private/" + video.getId();
        assertThat(reloaded.getStoragePrefix()).isEqualTo(newPrefix);
        assertThat(reloaded.getPlaylistKey()).isEqualTo(newPrefix + "/master.m3u8");

        assertThatThrownBy(() -> headObject(oldKey)).isInstanceOf(NoSuchKeyException.class);
        headObject(newPrefix + "/master.m3u8");
    }

    @Test
    void updateByForeignUserReturnsForbidden() throws Exception {
        Video video = seedVideo(saveUser(), Visibility.PUBLIC);
        String strangerToken = registerAndLogin();

        mockMvc.perform(patch("/api/videos/" + video.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateVideoRequest(
                                "Hijacked", null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRemovesVideoAndStorageObjects() throws Exception {
        String accessToken = registerAndLogin();
        Video video = seedVideo(currentUser(), Visibility.PUBLIC);
        String key = video.getStoragePrefix() + "/master.m3u8";
        UUID videoId = video.getId();

        mockMvc.perform(delete("/api/videos/" + videoId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        assertThat(videoRepository.findById(videoId)).isEmpty();
        assertThatThrownBy(() -> headObject(key)).isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void deleteWithOpenReportReturnsConflict() throws Exception {
        String accessToken = registerAndLogin();
        User owner = currentUser();
        Video video = seedVideo(owner, Visibility.PUBLIC);
        reportRepository.save(new Report(video, owner, "SPAM", "looks like spam"));

        mockMvc.perform(delete("/api/videos/" + video.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isConflict());

        assertThat(videoRepository.findById(video.getId())).isPresent();
    }

    @Test
    void deleteByForeignUserReturnsForbidden() throws Exception {
        Video video = seedVideo(saveUser(), Visibility.PUBLIC);
        String strangerToken = registerAndLogin();

        mockMvc.perform(delete("/api/videos/" + video.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    private void headObject(String key) {
        s3Client.headObject(HeadObjectRequest.builder().bucket(s3Properties.bucket()).key(key).build());
    }

    private Video seedVideo(User owner, Visibility visibility) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Manage Test", "manage-" + UUID.randomUUID(), visibility);
        video.setDescription("original description");
        videoRepository.save(video);
        String storagePrefix = (visibility == Visibility.PUBLIC ? "public/" : "private/") + video.getId();
        video.setStoragePrefix(storagePrefix);
        video.setStatus(VideoStatus.READY);
        video.setPlaylistKey(storagePrefix + "/master.m3u8");
        videoRepository.save(video);

        bucketInitializer.ensureReady();
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(storagePrefix + "/master.m3u8")
                        .build(),
                RequestBody.fromString("#EXTM3U", StandardCharsets.UTF_8));
        return video;
    }

    private User saveUser() {
        return userRepository.save(new User("manage-" + UUID.randomUUID() + "@example.com",
                "manage-" + UUID.randomUUID(), "irrelevant-hash"));
    }

    private User currentUser() {
        return userRepository.findByUsername(currentUsername).orElseThrow();
    }

    private String registerAndLogin() throws Exception {
        String email = "manage-test-" + UUID.randomUUID() + "@example.com";
        String username = "manage-test-" + UUID.randomUUID();
        currentUsername = username;
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
