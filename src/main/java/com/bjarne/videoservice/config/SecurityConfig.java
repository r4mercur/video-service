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
import java.util.UUID;

/**
 * Bis AP2 nur Actuator-Platzhalter, jetzt vollstaendiger Resource-Server:
 * selbst signierte RSA-JWTs (Nimbus) plus CSRF-Schutz, der auf die beiden
 * cookie-authentifizierten Pfade (refresh/logout) begrenzt ist.
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
     * Katalog-GETs sind laut CLAUDE.md Abschnitt 1 oeffentlich ohne Registrierung - auch wenn ein
     * Client einen abgelaufenen/ungueltigen Access-Token mitschickt (typisch bei 15-min-Tokens ohne
     * rechtzeitigen Refresh, siehe Abschnitt 8). Ohne diesen Resolver wuerde Spring Securitys
     * BearerTokenAuthenticationFilter einen ungueltigen Token mit 401 hart abweisen, noch bevor die
     * permitAll-Regel greift (manuell verifiziert). Fuer diese Pfade wird ein nicht dekodierbarer
     * Token daher wie "kein Token" behandelt -> Request laeuft anonym weiter. Auf allen anderen
     * Pfaden bleibt das Standardverhalten (harte 401 bei ungueltigem Token) unveraendert.
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
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/videos/*/view")
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                     BearerTokenResolver bearerTokenResolver,
                                                     JsonMapper jsonMapper) throws Exception {
        RequestMatcher csrfProtectedPaths = RequestMatchers.anyOf(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout")
        );

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfProtectedPaths)
                )
                // CsrfFilter ruft bei einem fehlgeschlagenen Check den ueber ExceptionHandlingConfigurer
                // konfigurierten AccessDeniedHandler auf. Scope bewusst auf csrfProtectedPaths begrenzt,
                // damit andere AccessDenied-Faelle (z.B. @PreAuthorize-Ownership-Checks) unveraendert
                // beim GlobalExceptionHandler landen.
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
                        .requestMatchers(HttpMethod.POST, "/api/videos/*/view").permitAll()
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
     * Der CsrfTokenRequestAttributeHandler laedt das CSRF-Token nur "deferred" -
     * ohne eine View, die es liest, wuerde der XSRF-TOKEN-Cookie nie an den
     * Client ausgeliefert. Erzwingt das Laden auf jedem Request (offizielles
     * Spring-Security-Muster fuer SPA/API-Backends ohne Server-Side-Views).
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
        log.warn("Kein app.auth.jwt-private-key-location/jwt-public-key-location konfiguriert - " +
                "generiere ein temporaeres RSA-Schluesselpaar im Speicher. NUR fuer lokale Entwicklung " +
                "geeignet, alle Access-Tokens werden beim naechsten Neustart ungueltig.");
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
