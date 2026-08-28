package com.bjarne.videoservice.transcoding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("ffmpeg")
class FfmpegRunnerTest {

    private final FfmpegRunner runner = new FfmpegRunner();

    @Test
    void runReturnsOutputOnSuccess() {
        String output = runner.run(List.of("ffmpeg", "-version"), Duration.ofSeconds(10));

        assertThat(output).containsIgnoringCase("ffmpeg version");
    }

    @Test
    void runThrowsOnNonZeroExitCode() {
        assertThatThrownBy(() -> runner.run(List.of("ffmpeg", "-not-a-real-flag"), Duration.ofSeconds(10)))
                .isInstanceOf(TranscodeProcessException.class);
    }

    @Test
    void runReportsProgressViaOnProgressCallback() {
        List<Duration> reported = new ArrayList<>();

        runner.run(List.of("ffmpeg", "-y", "-f", "lavfi",
                        "-i", "testsrc=duration=3:size=320x240:rate=25",
                        "-c:v", "libx264", "-preset", "ultrafast", "-f", "null", "-"),
                Duration.ofSeconds(30), reported::add);

        assertThat(reported).isNotEmpty();
        assertThat(reported.getLast()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void runThrowsAndKillsProcessOnTimeout() {
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> runner.run(List.of("ffmpeg", "-y", "-f", "lavfi",
                        "-i", "testsrc=duration=30:size=640x480:rate=25", "-c:v", "libx264", "-f", "null", "-"),
                Duration.ofMillis(300)))
                .isInstanceOf(TranscodeProcessException.class)
                .hasMessageContaining("timed out");

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(10_000);
    }
}
