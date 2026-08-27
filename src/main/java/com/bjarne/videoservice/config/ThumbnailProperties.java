package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.thumbnail")
public record ThumbnailProperties(
        @DefaultValue("8388608") long maxSizeBytes) {
}
