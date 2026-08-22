package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.Category;
import com.bjarne.videoservice.catalog.CategoryRepository;
import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.Visibility;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractS3IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("ffmpeg")
class TranscodeServiceIntegrationTest extends AbstractS3IntegrationTest {

    @Autowired
    private TranscodeService transcodeService;

    @Autowired
    private TranscodeJobRepository transcodeJobRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private S3BucketInitializer bucketInitializer;

    private final FfmpegRunner fixtureRunner = new FfmpegRunner();

    @Test
    void sourceBelowSmallestRungProducesSingleNativeResolutionRendition(@TempDir Path tempDir) {
        Video video = seedVideo();
        Path clip = tempDir.resolve("low-res.mp4");
        fixtureRunner.run(List.of("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-shortest", "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac",
                clip.toString()), Duration.ofSeconds(30));
        uploadSource(video, clip);

        TranscodeOutcome outcome = transcodeService.process(video.getId());

        assertThat(outcome.renditions()).hasSize(1);
        assertThat(outcome.renditions().get(0).height()).isEqualTo(240);
        assertThat(outcome.sourceInfo().width()).isEqualTo(320);
        assertThat(outcome.hasThumbnail()).isTrue();
        assertObjectExists(video.getStoragePrefix() + "/master.m3u8");
        assertObjectExists(video.getStoragePrefix() + "/240p/playlist.m3u8");
        assertObjectExists(video.getStoragePrefix() + "/thumbnail.jpg");
    }

    @Test
    void hdSourceAlreadyH264HighAacStreamCopiesTopRendition(@TempDir Path tempDir) {
        Video video = seedVideo();
        Path clip = tempDir.resolve("hd.mp4");
        fixtureRunner.run(List.of("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=1280x720:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-shortest", "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-profile:v", "high", "-preset", "veryfast",
                "-c:a", "aac", "-b:a", "128k",
                clip.toString()), Duration.ofSeconds(30));
        uploadSource(video, clip);

        TranscodeOutcome outcome = transcodeService.process(video.getId());

        assertThat(outcome.renditions()).extracting(PackagedRendition::height).containsExactlyInAnyOrder(360, 720);
        assertObjectExists(video.getStoragePrefix() + "/master.m3u8");
        assertObjectExists(video.getStoragePrefix() + "/360p/playlist.m3u8");
        assertObjectExists(video.getStoragePrefix() + "/720p/playlist.m3u8");
    }

    @Test
    void processWithJobIdWritesFullProgressOnSuccess(@TempDir Path tempDir) {
        Video video = seedVideo();
        TranscodeJob job = transcodeJobRepository.save(new TranscodeJob(video, Instant.now()));
        Path clip = tempDir.resolve("progress.mp4");
        fixtureRunner.run(List.of("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-shortest", "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac",
                clip.toString()), Duration.ofSeconds(30));
        uploadSource(video, clip);

        transcodeService.process(video.getId(), job.getId());

        TranscodeJob reloaded = transcodeJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProgressPercent()).isEqualTo(100);
        assertThat(reloaded.getCurrentStep()).isEqualTo("Fertig");
    }

    private void uploadSource(Video video, Path clip) {
        bucketInitializer.ensureReady();
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(video.getSourceKey())
                .contentType("video/mp4")
                .build(), RequestBody.fromFile(clip));
    }

    private void assertObjectExists(String key) {
        s3Client.headObject(HeadObjectRequest.builder().bucket(s3Properties.bucket()).key(key).build());
    }

    private Video seedVideo() {
        User user = userRepository.save(new User("transcode-" + UUID.randomUUID() + "@example.com",
                "transcode-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "Transcode Test", "transcode-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);
        video.setStoragePrefix("public/" + video.getId());
        video.setSourceKey(video.getStoragePrefix() + "/source.mp4");
        return videoRepository.save(video);
    }
}
