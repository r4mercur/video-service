package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Profile photo upload (CLAUDE.md 9.8). The cap applies to the raw upload; what ends up in the
 * bucket is always a small normalized JPEG. Must stay below spring.servlet.multipart.max-file-size,
 * otherwise the servlet container rejects the request before this limit can produce its own
 * message.
 */
@ConfigurationProperties(prefix = "app.avatar")
public record AvatarProperties(
        @DefaultValue("5242880") long maxSizeBytes,
        @DefaultValue("256") int sizePixels) {
}
