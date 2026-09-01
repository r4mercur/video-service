package com.bjarne.videoservice.moderation;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.identity.JwtService;
import com.bjarne.videoservice.identity.Role;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class AdminControllerIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    void blockVideoSetsStatusBlockedAndWritesAuditLog() throws Exception {
        String adminToken = adminToken();
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/admin/videos/" + video.getId() + "/block")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("copyright violation")))
                .andExpect(status().isNoContent());

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VideoStatus.BLOCKED);
        assertThat(reloaded.getVisibility()).isEqualTo(Visibility.PUBLIC);

        assertThat(auditLogRepository.findAll()).anyMatch(entry ->
                entry.getAction() == AuditLogAction.VIDEO_BLOCKED
                        && entry.getVideo().getId().equals(video.getId())
                        && entry.getReason().equals("copyright violation"));
    }

    @Test
    void blockVideoByNonAdminReturnsForbidden() throws Exception {
        String userToken = registerAndLogin();
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/admin/videos/" + video.getId() + "/block")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("abuse")))
                .andExpect(status().isForbidden());
    }

    @Test
    void blockingAlreadyBlockedVideoReturnsConflict() throws Exception {
        String adminToken = adminToken();
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);
        video.setStatus(VideoStatus.BLOCKED);
        videoRepository.save(video);

        mockMvc.perform(post("/api/admin/videos/" + video.getId() + "/block")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("again")))
                .andExpect(status().isConflict());
    }

    @Test
    void unblockVideoRestoresReadyWithoutTouchingVisibility() throws Exception {
        String adminToken = adminToken();
        Video video = seedReadyVideo(saveUser(), Visibility.PRIVATE);
        video.setStatus(VideoStatus.BLOCKED);
        videoRepository.save(video);

        mockMvc.perform(post("/api/admin/videos/" + video.getId() + "/unblock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("false positive")))
                .andExpect(status().isNoContent());

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(reloaded.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void unblockingNonBlockedVideoReturnsConflict() throws Exception {
        String adminToken = adminToken();
        Video video = seedReadyVideo(saveUser(), Visibility.PUBLIC);

        mockMvc.perform(post("/api/admin/videos/" + video.getId() + "/unblock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("noop")))
                .andExpect(status().isConflict());
    }

    @Test
    void dismissReportMarksDismissedWithoutTouchingVideo() throws Exception {
        String adminToken = adminToken();
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PUBLIC);
        Report report = reportRepository.save(new Report(video, owner, "SPAM", "detail"));

        mockMvc.perform(post("/api/admin/reports/" + report.getId() + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("not actually spam")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(VideoStatus.READY);
    }

    @Test
    void upholdingReportBlocksVideoAndMarksReportReviewed() throws Exception {
        String adminToken = adminToken();
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PUBLIC);
        Report report = reportRepository.save(new Report(video, owner, "ILLEGAL_CONTENT", "detail"));

        mockMvc.perform(post("/api/admin/reports/" + report.getId() + "/uphold")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("confirmed violation")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(VideoStatus.BLOCKED);
        assertThat(auditLogRepository.findAll()).anyMatch(e -> e.getAction() == AuditLogAction.REPORT_UPHELD)
                .anyMatch(e -> e.getAction() == AuditLogAction.VIDEO_BLOCKED);
    }

    @Test
    void resolvingAlreadyResolvedReportReturnsConflict() throws Exception {
        String adminToken = adminToken();
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PUBLIC);
        Report report = reportRepository.save(new Report(video, owner, "SPAM", null));
        report.setStatus(ReportStatus.DISMISSED);
        reportRepository.save(report);

        mockMvc.perform(post("/api/admin/reports/" + report.getId() + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("again")))
                .andExpect(status().isConflict());
    }

    @Test
    void listReportsFiltersByStatus() throws Exception {
        String adminToken = adminToken();
        User owner = saveUser();
        Video video = seedReadyVideo(owner, Visibility.PUBLIC);
        reportRepository.save(new Report(video, owner, "SPAM", "open one"));
        Report dismissed = reportRepository.save(new Report(video, owner, "OTHER", "dismissed one"));
        dismissed.setStatus(ReportStatus.DISMISSED);
        reportRepository.save(dismissed);

        mockMvc.perform(get("/api/admin/reports").param("status", "OPEN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].detail").value("open one"));
    }

    @Test
    void categoryAdminCrudCreatesUpdatesAndListsIncludingInactive() throws Exception {
        String adminToken = adminToken();

        String createResponse = mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"esport","name":"E-Sport","sortOrder":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = ((Number) JsonPath.read(createResponse, "$.id")).longValue();

        mockMvc.perform(patch("/api/admin/categories/" + categoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("E-Sport"));

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'esport')].active").value(false));
    }

    @Test
    void categoryAdminCanFlagCategoryAsAgeRestricted() throws Exception {
        String adminToken = adminToken();

        String createResponse = mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"nsfw-test","name":"NSFW Test","sortOrder":900}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ageRestricted").value(false))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = ((Number) JsonPath.read(createResponse, "$.id")).longValue();

        mockMvc.perform(patch("/api/admin/categories/" + categoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ageRestricted":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ageRestricted").value(true))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'nsfw-test')].ageRestricted").value(true));
    }

    @Test
    void createCategoryWithDuplicateSlugReturnsConflict() throws Exception {
        String adminToken = adminToken();

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"gaming","name":"Gaming Again","sortOrder":1}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCategoryEndpointsRejectNonAdmin() throws Exception {
        String userToken = registerAndLogin();

        mockMvc.perform(get("/api/admin/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    private String reasonJson(String reason) throws Exception {
        return objectMapper.writeValueAsString(new ModerationActionRequest(reason));
    }

    private Video seedReadyVideo(User owner, Visibility visibility) {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(owner, category, "Admin Test", "admin-" + UUID.randomUUID(), visibility);
        video.setStatus(VideoStatus.READY);
        video.setPublishedAt(Instant.now());
        return videoRepository.save(video);
    }

    private User saveUser() {
        return userRepository.save(new User("admin-owner-" + UUID.randomUUID() + "@example.com",
                "admin-owner-" + UUID.randomUUID(), "irrelevant-hash"));
    }

    private String adminToken() {
        User admin = userRepository.save(new User("admin-" + UUID.randomUUID() + "@example.com",
                "admin-" + UUID.randomUUID(), "irrelevant-hash"));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        return jwtService.generateAccessToken(admin);
    }

    private String registerAndLogin() {
        User user = userRepository.save(new User("plain-user-" + UUID.randomUUID() + "@example.com",
                "plain-user-" + UUID.randomUUID(), "irrelevant-hash"));
        return jwtService.generateAccessToken(user);
    }
}
