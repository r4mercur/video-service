package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.api")
public record ApiProperties(
        @DefaultValue("http://localhost:4200") List<String> corsAllowedOrigins) {
}
