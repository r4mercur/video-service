package com.bjarne.videoservice.identity;

import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerCreatesUser() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, uniqueUsername(), "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void registerWithDuplicateEmailReturnsConflict() throws Exception {
        String email = uniqueEmail();
        String body = registerJson(email, uniqueUsername(), "password123");
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, uniqueUsername(), "password123")))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsAccessTokenAndSecureRefreshCookie() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("Path=/api/auth");
    }

    @Test
    void refreshRotatesTokenAndOldTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");
        MvcResult loginResult = login(email, "password123");
        String oldRefreshToken = extractCookieValue(loginResult, "refresh_token");

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", oldRefreshToken))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String newRefreshToken = extractCookieValue(refreshResult, "refresh_token");
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", oldRefreshToken))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshReuseDetectionRevokesEntireChain() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");
        MvcResult loginResult = login(email, "password123");
        String tokenA = extractCookieValue(loginResult, "refresh_token");

        MvcResult firstRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokenA)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String tokenB = extractCookieValue(firstRefresh, "refresh_token");

        MvcResult secondRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokenB)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String tokenC = extractCookieValue(secondRefresh, "refresh_token");

        // Reuse of the already-rotated tokenA must be rejected...
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokenA)).with(csrf()))
                .andExpect(status().isUnauthorized());

        // ...and must revoke the currently active descendant (tokenC) too.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokenC)).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTokenSoSubsequentRefreshFails() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");
        MvcResult loginResult = login(email, "password123");
        String refreshToken = extractCookieValue(loginResult, "refresh_token");

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("refresh_token", refreshToken)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCsrfTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        registerUser(email, uniqueUsername(), "password123");
        MvcResult loginResult = login(email, "password123");
        String refreshToken = extractCookieValue(loginResult, "refresh_token");

        // The exact status (401 vs. 403) differs between MockMvc and the real embedded server:
        // for anonymous requests, ExceptionTranslationFilter routes CSRF errors through the
        // AuthenticationEntryPoint (401 + WWW-Authenticate) instead of the AccessDeniedHandler
        // (403) - manually verified against the real server via curl (401). MockMvc doesn't
        // reproduce this filter-chain detail identically, so this deliberately only checks for
        // "rejected" (4xx) instead of a specific code.
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void meWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsOwnProfile() throws Exception {
        String email = uniqueEmail();
        String username = uniqueUsername();
        registerUser(email, username, "password123");
        MvcResult loginResult = login(email, "password123");
        String accessToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.username").value(username));
    }

    private void registerUser(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, username, password)))
                .andExpect(status().isCreated());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String extractCookieValue(MvcResult result, String cookieName) {
        Cookie cookie = result.getResponse().getCookie(cookieName);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private String registerJson(String email, String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new RegisterRequest(email, username, password));
    }

    private String loginJson(String identifier, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(identifier, password));
    }

    private String uniqueEmail() {
        return "auth-test-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueUsername() {
        return "auth-test-" + UUID.randomUUID();
    }
}
