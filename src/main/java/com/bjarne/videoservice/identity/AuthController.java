package com.bjarne.videoservice.identity;

import com.bjarne.videoservice.shared.RateLimiter;
import com.bjarne.videoservice.shared.exceptions.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request,
                                                  HttpServletRequest httpRequest) {
        if (!rateLimiter.tryConsumeRegister(httpRequest.getRemoteAddr())) {
            throw new TooManyRequestsException("Zu viele Registrierungen - bitte spaeter erneut versuchen");
        }
        UserResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                       HttpServletRequest httpRequest) {
        if (!rateLimiter.tryConsumeLogin(httpRequest.getRemoteAddr())) {
            throw new TooManyRequestsException("Zu viele Login-Versuche - bitte spaeter erneut versuchen");
        }
        AuthService.LoginResult result = authService.login(request, httpRequest.getHeader(HttpHeaders.USER_AGENT));
        ResponseCookie cookie = refreshCookie(result.refreshToken(), Duration.ofDays(30));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AccessTokenResponse(result.accessToken(), result.expiresInSeconds()));
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        AuthService.RefreshResult result = authService.refresh(refreshToken, httpRequest.getHeader(HttpHeaders.USER_AGENT));
        ResponseCookie cookie = refreshCookie(result.refreshToken(), Duration.ofDays(30));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AccessTokenResponse(result.accessToken(), result.expiresInSeconds()));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        ResponseCookie cookie = refreshCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @GetMapping("/api/me")
    public UserResponse me(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        return authService.getCurrentUser(UUID.fromString(jwt.getSubject()));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
