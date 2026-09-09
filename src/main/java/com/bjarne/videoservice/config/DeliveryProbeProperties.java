package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the synthetic delivery probe (see DeliveryOriginProbe). Separate from
 * {@link DeliveryProperties} so the probe can be switched off entirely in an environment where an
 * outbound HTTP call on a timer is unwanted (tests, a laptop offline) without touching playback
 * configuration.
 */
@ConfigurationProperties(prefix = "app.delivery.probe")
public record DeliveryProbeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT60S") Duration interval,

        /*
         * The key the probe requests. The default deliberately does not exist: measurements
         * against production showed a request for a missing key is just as slow as one for a real
         * segment (a 403 took 6-11 s, the same as a 300 kB object), because the latency sits in
         * the request path, not in reading bytes. Probing a nonexistent key therefore measures
         * the same thing while needing no canary object to be uploaded, kept alive, or excluded
         * from lifecycle rules.
         *
         * Point this at a real, small, permanent object if you also want to catch the case where
         * the endpoint answers quickly but object reads are slow - the two are distinguishable
         * only if the probe actually reads an object.
         */
        @DefaultValue("probe/origin-latency-canary") String key,

        /*
         * Deliberately generous. A probe that gives up at 4 s would report "failed" for exactly
         * the 6-10 s responses that are the interesting signal - the timing is the finding, so
         * the probe has to stay on the line long enough to record it. Playback is already broken
         * well below this value; the alert threshold, not the timeout, is what defines "bad".
         */
        @DefaultValue("PT30S") Duration timeout) {
}
