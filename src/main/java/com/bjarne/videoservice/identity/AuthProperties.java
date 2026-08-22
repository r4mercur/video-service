package com.bjarne.videoservice.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("video-service") String issuer,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTokenTtl,
        Resource jwtPrivateKeyLocation,
        Resource jwtPublicKeyLocation) {
}
