package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractS3IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Constructs SourceRetentionCleanupJob directly (not via @Autowired) so each test can supply its
 * own retention window without fighting Spring's single shared TranscodeProperties bean -
 * TranscodeProperties is a plain record, same approach MediaProbeTest already uses.
 */
@Transactional
class SourceRetentionCleanupJobTest extends AbstractS3IntegrationTest {

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoragePrefixMover storagePrefixMover;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private Clock clock;

    @Test
    void deletesSourceOnceRetentionWindowHasPassed() {
        Video video = seedVideoWithSource();

        job(Duration.ZERO).deleteExpiredSources();

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getSourceDeletedAt()).isNotNull();
        assertThatThrownBy(() -> headSource(video)).isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void leavesSourceAloneWithinRetentionWindow() {
        Video video = seedVideoWithSource();

        job(Duration.ofDays(30)).deleteExpiredSources();

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getSourceDeletedAt()).isNull();
        assertThatCode(() -> headSource(video)).doesNotThrowAnyException();
    }

    @Test
    void isANoOpForAVideoThatHasNoSourceKey() {
        User user = userRepository.save(new User("retention-" + UUID.randomUUID() + "@example.com",
                "retention-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "No Source Yet", "retention-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);

        assertThatCode(() -> job(Duration.ZERO).deleteExpiredSources()).doesNotThrowAnyException();

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getSourceDeletedAt()).isNull();
    }

    private void headSource(Video video) {
        s3Client.headObject(HeadObjectRequest.builder().bucket(s3Properties.bucket())
                .key(video.getSourceKey()).build());
    }

    private Video seedVideoWithSource() {
        User user = userRepository.save(new User("retention-" + UUID.randomUUID() + "@example.com",
                "retention-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "Retention Test", "retention-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);

        String sourceKey = "source/" + video.getId() + "/source.mp4";
        s3Client.putObject(PutObjectRequest.builder().bucket(s3Properties.bucket()).key(sourceKey).build(),
                RequestBody.fromString("fake-source-bytes"));
        video.setSourceKey(sourceKey);
        videoRepository.save(video);
        return video;
    }

    private SourceRetentionCleanupJob job(Duration retention) {
        TranscodeProperties properties = new TranscodeProperties("ffmpeg", "ffprobe", null,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(2),
                List.of(Duration.ofMinutes(1)), List.of(360, 720, 1080), retention, 10, 10, 160, 90);
        return new SourceRetentionCleanupJob(videoRepository, storagePrefixMover, properties, clock);
    }
}
