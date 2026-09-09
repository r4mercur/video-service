package com.bjarne.videoservice.delivery.service;

import com.bjarne.videoservice.config.DeliveryProbeProperties;
import com.bjarne.videoservice.config.S3Properties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Periodically asks the object storage origin for one small object and records how long it takes
 * to answer.
 *
 * <p><b>Why this exists.</b> Segments, playlists and thumbnails are fetched by the browser
 * directly from the storage endpoint - Caddy and this application are deliberately not in the
 * media data path (CLAUDE.md 9.3). The consequence is that the JVM never sees a single media
 * request, so none of the existing HTTP metrics cover delivery at all: an origin can be returning
 * 503s and taking ten seconds per segment while {@code http_server_requests} stays perfectly
 * green and no alert fires. That is not a hypothetical - it is what production was doing when
 * this probe was written, and the only reason it was found is that someone watched a video and
 * complained. This is the one signal that closes that gap.
 *
 * <p><b>What it measures.</b> Wall-clock time for a complete HTTP request against
 * {@code storage.public-base-url}, from the same network position the application runs in. It is
 * a lower bound on what a viewer experiences, not a substitute for it: a viewer is further away
 * and shares the origin with everyone else. Real per-viewer numbers come from PlaybackTelemetry,
 * which reports what hls.js actually observed; the two are complementary and disagreeing values
 * are themselves informative (origin fine, viewers slow -> the problem is between them).
 *
 * <p>Runs on the scheduler, which {@code WorkerConfig} enables only for the {@code worker}
 * profile. In Phase 0 that is the same container Prometheus scrapes (compose.prod.yaml runs
 * {@code api,worker}), so the metric is exported where it is expected. If the worker is ever
 * split into its own container (CLAUDE.md 3.1) this probe travels with it and that container
 * needs its own scrape target - the same caveat JobPoller documents for its job metrics.
 */
@Component
public class DeliveryOriginProbe {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOriginProbe.class);
    private static final String TIMER_NAME = "videoservice.delivery.origin.request";

    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;
    private final DeliveryProbeProperties properties;
    private final String probeUrl;

    public DeliveryOriginProbe(S3Properties s3Properties, DeliveryProbeProperties properties,
                               MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.probeUrl = join(s3Properties.publicBaseUrl(), properties.key());
        this.httpClient = HttpClient.newBuilder()
                // A fresh connection per probe would fold DNS and TLS setup into every sample and
                // hide a change in server-side latency behind handshake noise. NEVER keeps the
                // client from following a redirect into somewhere unmeasured.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout())
                .build();
    }

    @Scheduled(fixedDelayString = "${app.delivery.probe.interval:PT60S}")
    public void probe() {
        if (!properties.enabled()) {
            return;
        }

        long start = System.nanoTime();
        String outcome;
        try {
            HttpResponse<Void> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(probeUrl))
                            .GET()
                            .timeout(properties.timeout())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            // Any HTTP answer means the origin is reachable and responded - including the 403 a
            // bucket returns for a missing key when the caller may not list it, which is the
            // expected reply for the default probe key. Only 5xx counts as the origin failing;
            // what we are really recording either way is how long it took.
            outcome = response.statusCode() >= 500 ? "server_error" : "ok";
        } catch (java.net.http.HttpTimeoutException e) {
            outcome = "timeout";
        } catch (Exception e) {
            // Interrupt handling: send() throws InterruptedException on shutdown - restore the
            // flag so the scheduler's thread pool can actually stop.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            outcome = "error";
            log.warn("Delivery origin probe to {} failed: {}", probeUrl, e.toString());
        }

        Timer.builder(TIMER_NAME)
                .tag("outcome", outcome)
                .description("Round-trip time of a synthetic request against the media origin, "
                        + "the delivery path browsers use directly and the JVM otherwise never sees")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - start));
    }

    private static String join(String baseUrl, String key) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/" + (key.startsWith("/") ? key.substring(1) : key);
    }
}
