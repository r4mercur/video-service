package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.upload")
public record UploadProperties(
        @DefaultValue("3145728000") long maxSizeBytes,
        @DefaultValue("104857600") long partSizeBytes,
        @DefaultValue("PT3H") Duration partUrlTtl,
        @DefaultValue("P1D") Duration sessionTtl) {
}
