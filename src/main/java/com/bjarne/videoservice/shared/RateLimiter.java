package com.bjarne.videoservice.shared;

import com.bjarne.videoservice.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * In-process rate limiting (bucket4j + Caffeine) - no Redis, analogous to the PlaylistObjectStore
 * decision in AP6: with a single API instance a shared cache brings no benefit, just extra
 * infra. One bucket per key (IP for login/register, user ID for reports); Caffeine evicts
 * inactive buckets itself after twice the refill period.
 */
@Component
public class RateLimiter {

    private final RateLimitProperties.Limit loginLimit;
    private final RateLimitProperties.Limit registerLimit;
    private final RateLimitProperties.Limit reportLimit;
    private final RateLimitProperties.Limit reportAnonymousLimit;

    private final Cache<String, Bucket> loginBuckets;
    private final Cache<String, Bucket> registerBuckets;
    private final Cache<String, Bucket> reportBuckets;
    private final Cache<String, Bucket> reportAnonymousBuckets;

    private final Counter loginRejections;
    private final Counter registerRejections;
    private final Counter reportRejections;
    private final Counter reportAnonymousRejections;

    public RateLimiter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.loginLimit = properties.login();
        this.registerLimit = properties.register();
        this.reportLimit = properties.report();
        this.reportAnonymousLimit = properties.reportAnonymous();
        this.loginBuckets = buildCache(loginLimit);
        this.registerBuckets = buildCache(registerLimit);
        this.reportBuckets = buildCache(reportLimit);
        this.reportAnonymousBuckets = buildCache(reportAnonymousLimit);
        this.loginRejections = rejectionCounter(meterRegistry, "login");
        this.registerRejections = rejectionCounter(meterRegistry, "register");
        this.reportRejections = rejectionCounter(meterRegistry, "report");
        this.reportAnonymousRejections = rejectionCounter(meterRegistry, "report_anonymous");
    }

    public boolean tryConsumeLogin(String ip) {
        return tryConsume(loginBuckets, ip, loginLimit, loginRejections);
    }

    public boolean tryConsumeRegister(String ip) {
        return tryConsume(registerBuckets, ip, registerLimit, registerRejections);
    }

    public boolean tryConsumeReport(String userId) {
        return tryConsume(reportBuckets, userId, reportLimit, reportRejections);
    }

    public boolean tryConsumeReportAnonymous(String ip) {
        return tryConsume(reportAnonymousBuckets, ip, reportAnonymousLimit, reportAnonymousRejections);
    }

    private boolean tryConsume(Cache<String, Bucket> cache, String key, RateLimitProperties.Limit limit,
                                Counter rejections) {
        boolean allowed = cache.get(key, k -> newBucket(limit)).tryConsume(1);
        if (!allowed) {
            rejections.increment();
        }
        return allowed;
    }

    private Counter rejectionCounter(MeterRegistry meterRegistry, String limiter) {
        return Counter.builder("videoservice.ratelimit.rejected")
                .tag("limiter", limiter)
                .description("Requests rejected by rate limiting, per limiter")
                .register(meterRegistry);
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
