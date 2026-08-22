package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.view-count")
public record ViewCountProperties(@DefaultValue("P1D") Duration dedupWindow) {
}
