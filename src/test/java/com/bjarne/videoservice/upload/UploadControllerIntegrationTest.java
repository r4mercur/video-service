package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.CategoryRepository;
import com.bjarne.videoservice.catalog.Visibility;
import com.bjarne.videoservice.identity.LoginRequest;
import com.bjarne.videoservice.identity.RegisterRequest;
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
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class UploadControllerIntegrationTest extends AbstractS3IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void initiateWithValidDataReturnsPartUrls() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateJson("My Video", gamingCategoryId(), 20)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoId").isNotEmpty())
                .andExpect(jsonPath("$.parts.length()").value(1))
                .andExpect(jsonPath("$.parts[0].partNumber").value(1));
    }

    @Test
    void initiateWithOversizedFileReturnsBadRequest() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateJson("Too Big", gamingCategoryId(), 4_000_000_000L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiateWithUnknownCategoryReturnsNotFound() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateJson("No Category", 999_999L, 20)))
                .andExpect(status().isNotFound());
    }

    @Test
    void completeFullUploadFlowMarksVideoProcessing() throws Exception {
        String accessToken = registerAndLogin();
        byte[] content = "fake-video-bytes".getBytes();

        MvcResult initiateResult = mockMvc.perform(post("/api/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateJson("Complete Flow", gamingCategoryId(), content.length)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseJson = initiateResult.getResponse().getContentAsString();
        String videoId = JsonPath.read(responseJson, "$.videoId");
        String partUrl = JsonPath.read(responseJson, "$.parts[0].url");

        String eTag = uploadPart(partUrl, content);

        mockMvc.perform(post("/api/videos/" + videoId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(1, eTag)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/videos/" + videoId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void completeByForeignUserReturnsForbidden() throws Exception {
        String ownerToken = registerAndLogin();
        MvcResult initiateResult = mockMvc.perform(post("/api/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateJson("Owned Video", gamingCategoryId(), 20)))
                .andExpect(status().isCreated())
                .andReturn();
        String videoId = JsonPath.read(initiateResult.getResponse().getContentAsString(), "$.videoId");

        String strangerToken = registerAndLogin();
        mockMvc.perform(post("/api/videos/" + videoId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(1, "\"whatever\"")))
                .andExpect(status().isForbidden());
    }

    @Test
    void completeOnUnknownVideoReturnsNotFound() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/videos/" + UUID.randomUUID() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(1, "\"whatever\"")))
                .andExpect(status().isNotFound());
    }

    private String uploadPart(String url, byte[] content) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("ETag").orElseThrow();
    }

    private Long gamingCategoryId() {
        return categoryRepository.findBySlug("gaming").orElseThrow().getId();
    }

    private String registerAndLogin() throws Exception {
        String email = "upload-test-" + UUID.randomUUID() + "@example.com";
        String username = "upload-test-" + UUID.randomUUID();
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, username, "password123"))))
                .andExpect(status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");
    }

    private String initiateJson(String title, Long categoryId, long sizeBytes) throws Exception {
        return objectMapper.writeValueAsString(new InitiateUploadRequest(
                title, "description", categoryId, Visibility.PUBLIC,
                "movie.mp4", sizeBytes, "video/mp4"));
    }

    private String completeJson(int partNumber, String eTag) throws Exception {
        return objectMapper.writeValueAsString(new CompleteUploadRequest(List.of(new CompletedPartDto(partNumber, eTag))));
    }
}
