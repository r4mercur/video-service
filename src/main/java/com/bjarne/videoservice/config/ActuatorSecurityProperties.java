package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the dedicated Basic-Auth filter chain guarding /api/actuator/prometheus
 * (SecurityConfig#actuatorMetricsFilterChain) - separate from the JWT resource server, since
 * Prometheus's scrape config has no way to obtain a bearer token. In production this endpoint
 * is additionally blocked from the public internet at the Caddy layer (Caddyfile.prod); Basic
 * Auth here is defense-in-depth for the internal Docker-network path Prometheus actually uses.
 */
@ConfigurationProperties(prefix = "app.actuator")
public record ActuatorSecurityProperties(String metricsUsername, String metricsPassword) {
}
