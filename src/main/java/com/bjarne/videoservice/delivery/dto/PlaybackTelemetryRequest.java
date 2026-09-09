package com.bjarne.videoservice.delivery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One batch of playback observations from a single player, covering the window since the last
 * batch. The frontend must source these from hls.js's own fragment stats, not from the browser's
 * Resource Timing API: the storage origin sends no {@code Timing-Allow-Origin} header, so for
 * these cross-origin requests the browser zeroes out sizes and timing details (a measurement
 * against production returned {@code transferSize: 0} and {@code responseStart == requestStart}
 * for every segment). S3 has no way to add that header to object responses, so hls.js - which
 * times its own XHRs and therefore is not subject to that restriction - is the only source that
 * actually has the numbers.
 *
 * <p>Everything here is bounded, because the endpoint accepts anonymous input and feeds a metrics
 * registry. Unbounded values or free-form strings would let any caller inflate Prometheus
 * cardinality or the histogram's memory footprint.
 */
public record PlaybackTelemetryRequest(

        /* Rendition the player was on. Mapped onto the known ladder before it becomes a tag. */
        @Min(0) @Max(4320) Integer height,

        /* Capped: one batch is a reporting window, not a whole session's history. */
        @Size(max = 100) List<@Valid FragmentSample> fragments,

        @NotNull @Min(0) @Max(10_000) Integer stalls,

        @NotNull @Min(0) @Max(10_000) Integer fragmentErrors,

        /* Time from player init to first frame, if this batch is the first of a session. */
        @Min(0) @Max(600_000) Long startupMs) {

    public record FragmentSample(
            @NotNull @Min(0) @Max(600_000) Long loadMs,
            @NotNull @Min(0) @Max(1_073_741_824L) Long bytes) {
    }
}
