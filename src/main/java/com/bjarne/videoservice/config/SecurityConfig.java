package com.bjarne.videoservice.config;

import com.bjarne.videoservice.identity.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Just an Actuator placeholder up to AP2, now a full resource server:
 * self-signed RSA JWTs (Nimbus) plus CSRF protection limited to the two
 * cookie-authenticated paths (refresh/logout).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public RSAKey rsaKey(AuthProperties properties) throws GeneralSecurityException, java.io.IOException {
        KeyPair keyPair = properties.jwtPrivateKeyLocation() != null && properties.jwtPublicKeyLocation() != null
                ? loadKeyPair(properties.jwtPrivateKeyLocation(), properties.jwtPublicKeyLocation())
                : generateEphemeralKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }

    /**
     * Per CLAUDE.md section 1, catalog GETs are public without registration - even when a
     * client sends an expired/invalid access token (typical for 15-min tokens without a timely
     * refresh, see section 8). Without this resolver, Spring Security's
     * BearerTokenAuthenticationFilter would hard-reject an invalid token with 401 before the
     * permitAll rule even applies (manually verified). For these paths, a non-decodable token
     * is therefore treated like "no token" -> the request continues anonymously. On all other
     * paths the default behavior (hard 401 on an invalid token) remains unchanged.
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver(JwtDecoder jwtDecoder) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher optionalAuthPaths = RequestMatchers.anyOf(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/categories"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/videos"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/videos/*"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/*/videos"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/videos/*/manifest"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/videos/*/master.m3u8"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/videos/*/*/playlist.m3u8"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/videos/*/view"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/videos/*/report")
        );
        return request -> {
            String token = delegate.resolve(request);
            if (token == null || !optionalAuthPaths.matches(request)) {
                return token;
            }
            try {
                jwtDecoder.decode(token);
                return token;
            } catch (JwtException e) {
                return null;
            }
        };
    }

    /**
     * Needed as soon as the Angular frontend (localhost:4200) calls the API on a different
     * origin (localhost:8080) - without this bean the browser blocks the requests.
     * allowCredentials is required so the HttpOnly refresh cookie (section 8) is sent along
     * with /api/auth/refresh and /api/auth/logout.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(ApiProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.corsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                     BearerTokenResolver bearerTokenResolver,
                                                     CorsConfigurationSource corsConfigurationSource,
                                                     JsonMapper jsonMapper) throws Exception {
        RequestMatcher csrfProtectedPaths = RequestMatchers.anyOf(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout")
        );

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfProtectedPaths)
                )
                // On a failed check, CsrfFilter calls the AccessDeniedHandler configured via
                // ExceptionHandlingConfigurer. Scope is deliberately limited to csrfProtectedPaths,
                // so other AccessDenied cases (e.g. @PreAuthorize ownership checks) still end up
                // unchanged at the GlobalExceptionHandler.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAccessDeniedHandlerFor(new CsrfProblemDetailAccessDeniedHandler(jsonMapper),
                                csrfProtectedPaths))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories", "/api/videos", "/api/videos/*",
                                "/api/users/*/videos", "/api/videos/*/manifest", "/api/videos/*/master.m3u8",
                                "/api/videos/*/*/playlist.m3u8").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/videos/*/view", "/api/videos/*/report").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        ))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CsrfTokenRequestAttributeHandler only loads the CSRF token "deferred" -
     * without a view reading it, the XSRF-TOKEN cookie would never be delivered
     * to the client. Forces the load on every request (the official Spring
     * Security pattern for SPA/API backends without server-side views).
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                         FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("role");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    private KeyPair generateEphemeralKeyPair() throws NoSuchAlgorithmException {
        log.warn("app.auth.jwt-private-key-location/jwt-public-key-location not configured - " +
                "generating a temporary in-memory RSA key pair. Suitable ONLY for local development, " +
                "all access tokens will become invalid on the next restart.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private KeyPair loadKeyPair(Resource privateKeyResource, Resource publicKeyResource)
            throws GeneralSecurityException, java.io.IOException {
        return new KeyPair(readPublicKey(publicKeyResource), readPrivateKey(privateKeyResource));
    }

    private RSAPrivateKey readPrivateKey(Resource resource) throws GeneralSecurityException, java.io.IOException {
        byte[] decoded = decodePem(resource);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private RSAPublicKey readPublicKey(Resource resource) throws GeneralSecurityException, java.io.IOException {
        byte[] decoded = decodePem(resource);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(decoded));
    }

    private byte[] decodePem(Resource resource) throws java.io.IOException {
        String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
