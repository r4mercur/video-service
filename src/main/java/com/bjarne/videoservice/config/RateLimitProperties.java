package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Limit login, Limit register, Limit report, Limit reportAnonymous) {

    public record Limit(@DefaultValue("5") int capacity, @DefaultValue("PT15M") Duration refillPeriod) {
    }
}
