package com.bjarne.videoservice.identity.web;

import com.bjarne.videoservice.catalog.entity.Category;
import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.CategoryRepository;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.identity.entity.Role;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.identity.service.JwtService;
import com.bjarne.videoservice.identity.storage.AvatarStorage;
import com.bjarne.videoservice.moderation.dto.ModerationActionRequest;
import com.bjarne.videoservice.moderation.entity.AuditLogAction;
import com.bjarne.videoservice.moderation.repository.AuditLogRepository;
import com.bjarne.videoservice.shared.storage.CachePolicy;
import com.bjarne.videoservice.support.AbstractS3IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deliberately not @Transactional, unlike most controller tests here: the replaced/removed object
 * is deleted in an afterCommit hook (AvatarService), which a test-managed transaction that rolls
 * back would never trigger. Every test therefore works on its own freshly created user.
 */
@AutoConfigureMockMvc
class ProfileControllerIntegrationTest extends AbstractS3IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AvatarStorage avatarStorage;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Test
    @Tag("ffmpeg")
    void uploadStoresASquareJpegWithTheImmutablePolicy() throws Exception {
        User user = saveUser(Role.USER);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/me/avatar")
                        .file(png(800, 500))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(endsWith(".jpg")));

        String key = reload(user).getAvatarKey();
        assertThat(key).startsWith(CachePolicy.AVATAR_PREFIX + user.getId() + "/");
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(s3Properties.bucket()).key(key).build());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(object.asByteArray()));
        assertThat(stored.getWidth()).isEqualTo(256);
        assertThat(stored.getHeight()).isEqualTo(256);
        assertThat(object.response().cacheControl()).isEqualTo(CachePolicy.IMMUTABLE);
    }

    @Test
    @Tag("ffmpeg")
    void replacingAnAvatarDeletesThePreviousObject() throws Exception {
        User user = saveUser(Role.USER);
        uploadAvatar(user);
        String firstKey = reload(user).getAvatarKey();

        uploadAvatar(user);

        String secondKey = reload(user).getAvatarKey();
        assertThat(secondKey).isNotEqualTo(firstKey);
        head(secondKey);
        assertThatExceptionOfType(NoSuchKeyException.class).isThrownBy(() -> head(firstKey));
    }

    @Test
    @Tag("ffmpeg")
    void nonImageUploadIsRejectedAndLeavesNoAvatar() throws Exception {
        User user = saveUser(Role.USER);
        MockMultipartFile playlist = new MockMultipartFile("file", "me.png", "image/png",
                "#EXTM3U\n#EXTINF:10.0,\nfile:///etc/passwd\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/me/avatar")
                        .file(playlist)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isBadRequest());

        assertThat(reload(user).getAvatarKey()).isNull();
    }

    /**
     * The service-level cap (app.avatar.max-size-bytes). The container-level multipart limit and
     * its 413 can't be exercised here: MockMvc hands the file straight to the controller without
     * going through the servlet container's multipart parsing.
     */
    @Test
    void uploadAboveTheAvatarCapIsRejected() throws Exception {
        User user = saveUser(Role.USER);
        MockMultipartFile large = new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[6 * 1024 * 1024]);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/me/avatar")
                        .file(large)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isBadRequest());

        assertThat(reload(user).getAvatarKey()).isNull();
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/me/avatar").file(png(32, 32)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removingOwnAvatarClearsItAndDeletesTheObject() throws Exception {
        User user = saveUser(Role.USER);
        String key = seedAvatar(user);

        mockMvc.perform(delete("/api/me/avatar").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        assertThat(reload(user).getAvatarKey()).isNull();
        assertThatExceptionOfType(NoSuchKeyException.class).isThrownBy(() -> head(key));
    }

    @Test
    void meIncludesTheAvatarUrl() throws Exception {
        User user = saveUser(Role.USER);
        String key = seedAvatar(user);

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(endsWith("/" + key)));
    }

    /** Public, so it must never leak the email address that /api/me returns. */
    @Test
    void publicProfileIsAnonymousAndOmitsTheEmail() throws Exception {
        User user = saveUser(Role.USER);
        String key = seedAvatar(user);

        mockMvc.perform(get("/api/users/" + user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user.getUsername()))
                .andExpect(jsonPath("$.avatarUrl").value(endsWith("/" + key)))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void publicProfileOfUnknownUserIs404() throws Exception {
        mockMvc.perform(get("/api/users/does-not-exist-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void videoDetailCarriesTheOwnersAvatar() throws Exception {
        User owner = saveUser(Role.USER);
        String key = seedAvatar(owner);
        Video video = seedReadyVideo(owner);

        mockMvc.perform(get("/api/videos/" + video.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerAvatarUrl").value(endsWith("/" + key)));
    }

    @Test
    void adminRemovalDeletesTheAvatarAndWritesAnAuditEntry() throws Exception {
        User user = saveUser(Role.USER);
        String key = seedAvatar(user);
        User admin = saveUser(Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/" + user.getUsername() + "/avatar/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Offensive image"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());

        assertThat(reload(user).getAvatarKey()).isNull();
        assertThatExceptionOfType(NoSuchKeyException.class).isThrownBy(() -> head(key));
        assertThat(auditLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo(AuditLogAction.AVATAR_REMOVED);
                    assertThat(entry.getTargetUser().getId()).isEqualTo(user.getId());
                    assertThat(entry.getReason()).isEqualTo("Offensive image");
                });
    }

    @Test
    void adminRemovalWithoutAvatarIsAConflict() throws Exception {
        User user = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/" + user.getUsername() + "/avatar/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Nothing there"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void adminRemovalRequiresTheAdminRole() throws Exception {
        User user = saveUser(Role.USER);
        seedAvatar(user);
        User stranger = saveUser(Role.USER);

        mockMvc.perform(post("/api/admin/users/" + user.getUsername() + "/avatar/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Not allowed"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isForbidden());

        assertThat(reload(user).getAvatarKey()).isNotNull();
    }

    private void uploadAvatar(User user) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/me/avatar")
                        .file(png(64, 64))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk());
    }

    /** Puts an object and points the user at it directly, so non-ffmpeg tests need no transcoder. */
    private String seedAvatar(User user) throws Exception {
        String key = avatarStorage.newKey(user.getId());
        Path file = Files.createTempFile("avatar-seed-", ".jpg");
        try {
            Files.write(file, new byte[] {1, 2, 3});
            avatarStorage.put(key, file);
        } finally {
            Files.deleteIfExists(file);
        }
        user.setAvatarKey(key);
        userRepository.save(user);
        return key;
    }

    private Video seedReadyVideo(User owner) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Avatar Test", "avatar-" + UUID.randomUUID(), Visibility.PUBLIC);
        video.setStatus(VideoStatus.READY);
        video.setPublishedAt(Instant.now());
        return videoRepository.save(video);
    }

    private User saveUser(Role role) {
        User user = new User("profile-" + UUID.randomUUID() + "@example.com", "profile-" + UUID.randomUUID(),
                "irrelevant-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private HeadObjectResponse head(String key) {
        return s3Client.headObject(HeadObjectRequest.builder().bucket(s3Properties.bucket()).key(key).build());
    }

    private String reasonJson(String reason) throws Exception {
        return objectMapper.writeValueAsString(new ModerationActionRequest(reason));
    }

    private static MockMultipartFile png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "me.png", "image/png", out.toByteArray());
    }
}
