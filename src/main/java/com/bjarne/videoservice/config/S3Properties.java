package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.storage")
public record S3Properties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("true") boolean pathStyleAccess,
        List<String> corsAllowedOrigins,
        // Origin the BROWSER fetches public/* media from directly (CLAUDE.md 9.1/9.3) - distinct
        // from `endpoint` above, which is only the SDK's signing target for backend-side S3 calls.
        // Never assume they're the same host: production path-style-access=false means `endpoint`
        // has no bucket segment at all (S3Config builds that itself), while this one is the real,
        // bucket-specific public URL (matches Caddyfile.prod's STORAGE_PUBLIC_ORIGIN exactly - see
        // application-prod.properties).
        String publicBaseUrl) {
}
