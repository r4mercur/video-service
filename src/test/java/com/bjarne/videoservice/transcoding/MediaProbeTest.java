package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("ffmpeg")
class MediaProbeTest {

    private static Path videoWithAudio;
    private static Path videoOnlyNoAudio;

    private final FfmpegRunner ffmpegRunner = new FfmpegRunner();
    private final TranscodeProperties properties = new TranscodeProperties("ffmpeg", "ffprobe", null,
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(2),
            List.of(Duration.ofMinutes(1)), List.of(360, 720, 1080), Duration.ofDays(30), 10, 10, 160, 90);
    private final MediaProbe mediaProbe = new MediaProbe(ffmpegRunner, properties, new ObjectMapper());

    @BeforeAll
    static void generateFixtures(@TempDir Path tempDir) {
        FfmpegRunner runner = new FfmpegRunner();
        videoWithAudio = tempDir.resolve("with-audio.mp4");
        runner.run(List.of("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-shortest", "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-c:a", "aac",
                videoWithAudio.toString()), Duration.ofSeconds(30));

        videoOnlyNoAudio = tempDir.resolve("no-audio.mp4");
        runner.run(List.of("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-pix_fmt", "yuv420p", "-c:v", "libx264",
                videoOnlyNoAudio.toString()), Duration.ofSeconds(30));
    }

    @Test
    void probeExtractsResolutionDurationAndCodecs() {
        MediaInfo info = mediaProbe.probe(videoWithAudio);

        assertThat(info.hasVideoStream()).isTrue();
        assertThat(info.hasAudioStream()).isTrue();
        assertThat(info.width()).isEqualTo(320);
        assertThat(info.height()).isEqualTo(240);
        assertThat(info.durationSeconds()).isGreaterThan(1.0);
        assertThat(info.videoCodec()).isEqualToIgnoringCase("h264");
        assertThat(info.audioCodec()).isEqualToIgnoringCase("aac");
    }

    @Test
    void probeOnFileWithoutAudioReportsNoAudioStream() {
        MediaInfo info = mediaProbe.probe(videoOnlyNoAudio);

        assertThat(info.hasVideoStream()).isTrue();
        assertThat(info.hasAudioStream()).isFalse();
    }

    @Test
    void probeOnMissingFileThrowsMediaValidationException() {
        assertThatThrownBy(() -> mediaProbe.probe(Path.of("does-not-exist.mp4")))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void validateAcceptsPlausibleInfo() {
        MediaInfo info = new MediaInfo(true, true, 120, 1280, 720, "h264", "High", "aac");
        mediaProbe.validate(info);
    }

    @Test
    void validateRejectsMissingVideoStream() {
        MediaInfo info = new MediaInfo(false, true, 120, 0, 0, null, null, "aac");
        assertThatThrownBy(() -> mediaProbe.validate(info)).isInstanceOf(MediaValidationException.class);
    }

    @Test
    void validateRejectsDurationOverTwoHours() {
        MediaInfo info = new MediaInfo(true, true, 7201, 1280, 720, "h264", "High", "aac");
        assertThatThrownBy(() -> mediaProbe.validate(info)).isInstanceOf(MediaValidationException.class);
    }

    @Test
    void validateRejectsImplausibleResolution() {
        MediaInfo tooSmall = new MediaInfo(true, true, 60, 4, 4, "h264", "High", "aac");
        assertThatThrownBy(() -> mediaProbe.validate(tooSmall)).isInstanceOf(MediaValidationException.class);

        MediaInfo tooLarge = new MediaInfo(true, true, 60, 20000, 20000, "h264", "High", "aac");
        assertThatThrownBy(() -> mediaProbe.validate(tooLarge)).isInstanceOf(MediaValidationException.class);
    }
}
