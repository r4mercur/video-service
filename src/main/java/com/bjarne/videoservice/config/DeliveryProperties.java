package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.delivery")
public record DeliveryProperties(
        @DefaultValue("PT3H") Duration segmentUrlTtl,
        @DefaultValue("PT1M") Duration playlistCacheTtl,
        @DefaultValue("500") long playlistCacheMaxSize) {
}
