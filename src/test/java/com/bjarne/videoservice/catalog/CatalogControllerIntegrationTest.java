package com.bjarne.videoservice.catalog;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class CatalogControllerIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void categoriesReturnsSeededListOrderedBySortOrder() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("gaming"))
                .andExpect(jsonPath("$[1].slug").value("music"));
    }

    @Test
    void feedOnlyReturnsPublicReadyVideosNewestFirst() throws Exception {
        User owner = registerUser();
        Instant now = Instant.now();
        Video older = video(owner, "Older Public", VideoStatus.READY, Visibility.PUBLIC, now.minusSeconds(60));
        Video newer = video(owner, "Newer Public", VideoStatus.READY, Visibility.PUBLIC, now);
        video(owner, "Private", VideoStatus.READY, Visibility.PRIVATE, now);
        video(owner, "Still Processing", VideoStatus.PROCESSING, Visibility.PUBLIC, null);

        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].slug").value(newer.getSlug()))
                .andExpect(jsonPath("$.items[1].slug").value(older.getSlug()));
    }

    @Test
    void categoriesExposesAgeRestrictedFlag() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='gaming')].ageRestricted").value(false))
                .andExpect(jsonPath("$[?(@.slug=='adult')].ageRestricted").value(true));
    }

    @Test
    void feedExcludesAgeRestrictedCategoryByDefault() throws Exception {
        User owner = registerUser();
        Category adult = categoryRepository.findBySlug("adult").orElseThrow();
        video(owner, adult, "Adult Video", VideoStatus.READY, Visibility.PUBLIC, Instant.now());

        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void feedIncludesAgeRestrictedCategoryWhenOptedIn() throws Exception {
        User owner = registerUser();
        Category adult = categoryRepository.findBySlug("adult").orElseThrow();
        Video adultVideo = video(owner, adult, "Adult Video", VideoStatus.READY, Visibility.PUBLIC, Instant.now());

        mockMvc.perform(get("/api/videos").param("includeAgeRestricted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value(adultVideo.getSlug()))
                .andExpect(jsonPath("$.items[0].ageRestricted").value(true));
    }

    @Test
    void detailIsReachableForAgeRestrictedVideoWithoutOptIn() throws Exception {
        User owner = registerUser();
        Category adult = categoryRepository.findBySlug("adult").orElseThrow();
        Video adultVideo = video(owner, adult, "Adult Video", VideoStatus.READY, Visibility.PUBLIC, Instant.now());

        // Direct link is not gated by the discovery-page filter (CLAUDE.md: this is a browse
        // filter, not an access-control/age-verification mechanism).
        mockMvc.perform(get("/api/videos/" + adultVideo.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ageRestricted").value(true));
    }

    @Test
    void feedFiltersByCategory() throws Exception {
        User owner = registerUser();
        Instant now = Instant.now();
        Category gaming = categoryRepository.findBySlug("gaming").orElseThrow();
        Category music = categoryRepository.findBySlug("music").orElseThrow();
        Video gamingVideo = video(owner, gaming, "Gaming Video", VideoStatus.READY, Visibility.PUBLIC, now);
        video(owner, music, "Music Video", VideoStatus.READY, Visibility.PUBLIC, now);

        mockMvc.perform(get("/api/videos").param("category", "gaming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value(gamingVideo.getSlug()));
    }

    @Test
    void feedCursorPaginationHasNoOverlapBetweenPages() throws Exception {
        User owner = registerUser();
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            video(owner, "Video " + i, VideoStatus.READY, Visibility.PUBLIC, now.minus(i, ChronoUnit.SECONDS));
        }

        MvcResult firstPage = mockMvc.perform(get("/api/videos").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        String firstItemSlug = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.items[0].slug");
        String secondItemSlug = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.items[1].slug");

        MvcResult secondPage = mockMvc.perform(get("/api/videos").param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        String thirdItemSlug = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.items[0].slug");
        String fourthItemSlug = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.items[1].slug");

        assertThat(thirdItemSlug).isNotIn(firstItemSlug, secondItemSlug);
        assertThat(fourthItemSlug).isNotIn(firstItemSlug, secondItemSlug);
    }

    @Test
    void feedWithUnsupportedSortReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/videos").param("sort", "popular"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailOnForeignPrivateVideoReturnsNotFound() throws Exception {
        User owner = registerUser();
        Video privateVideo = video(owner, "Secret", VideoStatus.READY, Visibility.PRIVATE, Instant.now());

        mockMvc.perform(get("/api/videos/" + privateVideo.getSlug()))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailOnOwnPrivateVideoIsVisibleWithToken() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        String accessToken = registerAndLogin(email, username);
        User owner = userRepository.findByEmail(email).orElseThrow();
        Video privateVideo = video(owner, "Secret", VideoStatus.READY, Visibility.PRIVATE, Instant.now());

        mockMvc.perform(get("/api/videos/" + privateVideo.getSlug())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(privateVideo.getSlug()));
    }

    @Test
    void myVideosIncludesPrivateVideos() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        String accessToken = registerAndLogin(email, username);
        User owner = userRepository.findByEmail(email).orElseThrow();
        video(owner, "Mine Public", VideoStatus.READY, Visibility.PUBLIC, Instant.now());
        video(owner, "Mine Private", VideoStatus.READY, Visibility.PRIVATE, Instant.now());

        mockMvc.perform(get("/api/me/videos").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void channelOnlyShowsPublicVideos() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        registerAndLogin(email, username);
        User owner = userRepository.findByEmail(email).orElseThrow();
        Video publicVideo = video(owner, "Channel Public", VideoStatus.READY, Visibility.PUBLIC, Instant.now());
        video(owner, "Channel Private", VideoStatus.READY, Visibility.PRIVATE, Instant.now());

        mockMvc.perform(get("/api/users/" + username + "/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value(publicVideo.getSlug()));
    }

    @Test
    void channelExcludesAgeRestrictedCategoryByDefault() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        registerAndLogin(email, username);
        User owner = userRepository.findByEmail(email).orElseThrow();
        Category adult = categoryRepository.findBySlug("adult").orElseThrow();
        video(owner, adult, "Channel Adult", VideoStatus.READY, Visibility.PUBLIC, Instant.now());

        mockMvc.perform(get("/api/users/" + username + "/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void channelIncludesAgeRestrictedCategoryWhenOptedIn() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        registerAndLogin(email, username);
        User owner = userRepository.findByEmail(email).orElseThrow();
        Category adult = categoryRepository.findBySlug("adult").orElseThrow();
        Video adultVideo = video(owner, adult, "Channel Adult", VideoStatus.READY, Visibility.PUBLIC, Instant.now());

        mockMvc.perform(get("/api/users/" + username + "/videos").param("includeAgeRestricted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value(adultVideo.getSlug()));
    }

    private Video video(User owner, String title, VideoStatus status, Visibility visibility, Instant publishedAt) {
        return video(owner, categoryRepository.findBySlug("gaming").orElseThrow(), title, status, visibility, publishedAt);
    }

    private Video video(User owner, Category category, String title, VideoStatus status, Visibility visibility,
                         Instant publishedAt) {
        String slug = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID();
        Video video = new Video(owner, category, title, slug, visibility);
        video.setStatus(status);
        video.setPublishedAt(publishedAt);
        return videoRepository.save(video);
    }

    private User registerUser() throws Exception {
        String email = "catalog-test-" + UUID.randomUUID() + "@example.com";
        String username = "catalog-test-" + UUID.randomUUID();
        registerAndLogin(email, username);
        return userRepository.findByEmail(email).orElseThrow();
    }

    private String registerAndLogin(String email, String username) throws Exception {
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
