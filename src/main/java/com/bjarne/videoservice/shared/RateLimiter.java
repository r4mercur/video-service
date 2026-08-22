package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

/**
 * In-process Rate-Limiting (bucket4j + Caffeine) - kein Redis, analog zur PlaylistObjectStore-
 * Entscheidung in AP6: bei einer einzelnen API-Instanz kein Vorteil durch einen shared Cache,
 * nur zusaetzliche Infra. Ein Bucket pro Schluessel (IP bei Login/Register, User-ID bei Reports),
 * Caffeine raeumt inaktive Buckets nach dem Doppelten der Refill-Periode selbst auf.
 */
@Component
public class RateLimiter {

    private final RateLimitProperties.Limit loginLimit;
    private final RateLimitProperties.Limit registerLimit;
    private final RateLimitProperties.Limit reportLimit;

    private final Cache<String, Bucket> loginBuckets;
    private final Cache<String, Bucket> registerBuckets;
    private final Cache<String, Bucket> reportBuckets;

    public RateLimiter(RateLimitProperties properties) {
        this.loginLimit = properties.login();
        this.registerLimit = properties.register();
        this.reportLimit = properties.report();
        this.loginBuckets = buildCache(loginLimit);
        this.registerBuckets = buildCache(registerLimit);
        this.reportBuckets = buildCache(reportLimit);
    }

    public boolean tryConsumeLogin(String ip) {
        return tryConsume(loginBuckets, ip, loginLimit);
    }

    public boolean tryConsumeRegister(String ip) {
        return tryConsume(registerBuckets, ip, registerLimit);
    }

    public boolean tryConsumeReport(String userId) {
        return tryConsume(reportBuckets, userId, reportLimit);
    }

    private boolean tryConsume(Cache<String, Bucket> cache, String key, RateLimitProperties.Limit limit) {
        return cache.get(key, k -> newBucket(limit)).tryConsume(1);
    }

    private Bucket newBucket(RateLimitProperties.Limit limit) {
        Bandwidth bandwidth = Bandwidth.classic(limit.capacity(), Refill.intervally(limit.capacity(), limit.refillPeriod()));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private Cache<String, Bucket> buildCache(RateLimitProperties.Limit limit) {
        return Caffeine.newBuilder()
                .expireAfterAccess(limit.refillPeriod().multipliedBy(2))
                .maximumSize(10_000)
                .build();
    }
}
