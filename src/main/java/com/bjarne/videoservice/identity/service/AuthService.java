package com.bjarne.videoservice.identity.service;

import com.bjarne.videoservice.config.AuthProperties;
import com.bjarne.videoservice.identity.dto.LoginRequest;
import com.bjarne.videoservice.identity.dto.RegisterRequest;
import com.bjarne.videoservice.identity.dto.UserResponse;
import com.bjarne.videoservice.identity.entity.RefreshToken;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.RefreshTokenRepository;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.shared.exceptions.ConflictException;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.UnauthorizedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;
    private final Counter refreshReuseCounter;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthProperties properties,
                       Clock clock,
                       MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.clock = clock;
        this.loginSuccessCounter = Counter.builder("videoservice.auth.logins")
                .tag("result", "success")
                .description("Login attempts by result")
                .register(meterRegistry);
        this.loginFailureCounter = Counter.builder("videoservice.auth.logins")
                .tag("result", "failure")
                .description("Login attempts by result")
                .register(meterRegistry);
        // Every increment means a refresh token was presented twice - either a replayed/stolen
        // token or a badly misbehaving client. The single most alert-worthy signal in auth.
        this.refreshReuseCounter = Counter.builder("videoservice.auth.refresh.reuse")
                .description("Refresh token reuse detected (token family revoked)")
                .register(meterRegistry);
    }

    public record LoginResult(UserResponse user, String accessToken, long expiresInSeconds, String refreshToken) {
    }

    public record RefreshResult(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ConflictException("Email already in use");
        }
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ConflictException("Username already in use");
        }
        User user = new User(request.email(), request.username(), passwordEncoder.encode(request.password()));
        userRepository.save(user);
        return UserResponse.from(user);
    }

    @Transactional
    public LoginResult login(LoginRequest request, String userAgent) {
        User user = userRepository.findByEmail(request.identifier())
                .or(() -> userRepository.findByUsername(request.identifier()))
                .orElseThrow(() -> {
                    loginFailureCounter.increment();
                    return new UnauthorizedException("Invalid credentials");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailureCounter.increment();
            throw new UnauthorizedException("Invalid credentials");
        }

        loginSuccessCounter.increment();
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = issueRefreshToken(user, userAgent, null);
        return new LoginResult(UserResponse.from(user), accessToken, properties.accessTokenTtl().toSeconds(),
                refreshToken);
    }

    @Transactional
    public RefreshResult refresh(String presentedToken, String userAgent) {
        if (presentedToken == null) {
            throw new UnauthorizedException("Missing refresh token");
        }

        RefreshToken current = refreshTokenRepository.findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (current.getRevokedAt() != null) {
            revokeChainTail(current);
            refreshReuseCounter.increment();
            throw new UnauthorizedException("Refresh token reuse detected");
        }
        if (current.getExpiresAt().isBefore(clock.instant())) {
            throw new UnauthorizedException("Refresh token expired");
        }

        User user = current.getUser();
        String newRefreshToken = issueRefreshToken(user, userAgent, current);
        String accessToken = jwtService.generateAccessToken(user);
        return new RefreshResult(accessToken, properties.accessTokenTtl().toSeconds(), newRefreshToken);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        return UserResponse.from(user);
    }

    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(presentedToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(clock.instant());
                    refreshTokenRepository.save(token);
                });
    }

    private String issueRefreshToken(User user, String userAgent, RefreshToken previous) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken newToken = new RefreshToken(user, hash(rawToken),
                clock.instant().plus(properties.refreshTokenTtl()), userAgent);
        refreshTokenRepository.save(newToken);

        if (previous != null) {
            previous.setReplacedBy(newToken);
            previous.setRevokedAt(clock.instant());
            refreshTokenRepository.save(previous);
        }
        return rawToken;
    }

    private void revokeChainTail(RefreshToken token) {
        RefreshToken tail = token;
        while (tail.getReplacedBy() != null) {
            tail = tail.getReplacedBy();
        }
        if (tail.getRevokedAt() == null) {
            tail.setRevokedAt(clock.instant());
            refreshTokenRepository.save(tail);
        }
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
