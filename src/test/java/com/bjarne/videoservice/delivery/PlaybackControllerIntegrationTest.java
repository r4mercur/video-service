package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.identity.LoginRequest;
import com.bjarne.videoservice.identity.RegisterRequest;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Transactional
class PlaybackControllerIntegrationTest extends AbstractS3IntegrationTest {

    private static final String MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/playlist.m3u8
            """;

    private static final String RENDITION_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.000,
            segment_000.m4s
            #EXT-X-ENDLIST
            """;

    private static final String SEGMENT_BYTES = "0123456789";

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
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private S3BucketInitializer bucketInitializer;

    private String currentUsername;

    @Test
    void manifestForPublicReadyVideoReturnsStaticCaddyPath() throws Exception {
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PUBLIC);

        mockMvc.perform(get("/api/videos/" + video.getId() + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlistUrl").value("/" + video.getStoragePrefix() + "/master.m3u8"));
    }

    @Test
    void manifestForPrivateVideoAsOwnerReturnsDynamicApiPath() throws Exception {
        String accessToken = registerAndLogin();
        User owner = userRepository.findByUsername(currentUsername).orElseThrow();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);

        mockMvc.perform(get("/api/videos/" + video.getId() + "/manifest")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlistUrl").value("/api/videos/" + video.getId() + "/master.m3u8"));
    }

    @Test
    void manifestForPrivateVideoAsForeignUserReturnsNotFound() throws Exception {
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);
        String strangerToken = registerAndLogin();

        mockMvc.perform(get("/api/videos/" + video.getId() + "/manifest")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestForPrivateVideoAnonymousReturnsNotFound() throws Exception {
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);

        mockMvc.perform(get("/api/videos/" + video.getId() + "/manifest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestForNotYetProcessedVideoReturnsNotFound() throws Exception {
        User owner = saveUser();
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Processing", "processing-" + UUID.randomUUID(), Visibility.PUBLIC);
        video.setStatus(VideoStatus.PROCESSING);
        videoRepository.save(video);

        mockMvc.perform(get("/api/videos/" + video.getId() + "/manifest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestForUnknownVideoReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/videos/" + UUID.randomUUID() + "/manifest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void masterPlaylistDynamicEndpointReturnsRawContentWithNoStoreForOwner() throws Exception {
        String accessToken = registerAndLogin();
        User owner = userRepository.findByUsername(currentUsername).orElseThrow();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);

        mockMvc.perform(get("/api/videos/" + video.getId() + "/master.m3u8")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("application/vnd.apple.mpegurl"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("360p/playlist.m3u8"));
    }

    @Test
    void renditionPlaylistRewritesSegmentsToPresignedUrlsFetchableWithRange() throws Exception {
        String accessToken = registerAndLogin();
        User owner = userRepository.findByUsername(currentUsername).orElseThrow();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);

        MvcResult result = mockMvc.perform(get("/api/videos/" + video.getId() + "/360p/playlist.m3u8")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String rewritten = result.getResponse().getContentAsString();

        // No raw relative reference left - every segment/init line is now an absolute,
        // presigned storage URL (CLAUDE.md 9.3).
        assertThat(rewritten).doesNotContain("\nsegment_000.m4s\n");
        assertThat(rewritten).doesNotContain("URI=\"init.mp4\"");
        assertThat(rewritten).contains("X-Amz-Signature");

        String segmentUrl = extractUrl(rewritten, "segment_000\\.m4s[^\\s\"]*");
        assertRangeRequestReturnsPartialContent(segmentUrl);
    }

    @Test
    void renditionPlaylistForForeignUserOnPrivateVideoReturnsNotFound() throws Exception {
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PRIVATE);
        String strangerToken = registerAndLogin();

        mockMvc.perform(get("/api/videos/" + video.getId() + "/360p/playlist.m3u8")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    private void assertRangeRequestReturnsPartialContent(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=0-4")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.body()).isEqualTo(SEGMENT_BYTES.substring(0, 5));
    }

    private String extractUrl(String playlist, String markerRegex) {
        Pattern pattern = Pattern.compile("https?://\\S*" + markerRegex);
        Matcher matcher = pattern.matcher(playlist);
        assertThat(matcher.find()).as("presigned URL for " + markerRegex + " found in playlist text").isTrue();
        return matcher.group();
    }

    private Video seedReadyVideo(User owner, Visibility visibility) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Playback Test", "playback-" + UUID.randomUUID(), visibility);
        videoRepository.save(video);
        String storagePrefix = (visibility == Visibility.PUBLIC ? "public/" : "private/") + video.getId();
        video.setStoragePrefix(storagePrefix);
        video.setStatus(VideoStatus.READY);
        video.setPlaylistKey(storagePrefix + "/master.m3u8");
        videoRepository.save(video);

        uploadObject(storagePrefix + "/master.m3u8", MASTER_PLAYLIST);
        uploadObject(storagePrefix + "/360p/playlist.m3u8", RENDITION_PLAYLIST);
        uploadObject(storagePrefix + "/360p/init.mp4", "init-bytes");
        uploadObject(storagePrefix + "/360p/segment_000.m4s", SEGMENT_BYTES);
        return video;
    }

    private void uploadObject(String key, String content) {
        bucketInitializer.ensureReady();
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(key)
                .build(), RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    private User saveUser() {
        return userRepository.save(new User("playback-" + UUID.randomUUID() + "@example.com",
                "playback-" + UUID.randomUUID(), "irrelevant-hash"));
    }

    private String registerAndLogin() throws Exception {
        String email = "playback-test-" + UUID.randomUUID() + "@example.com";
        String username = "playback-test-" + UUID.randomUUID();
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
