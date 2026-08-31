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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

        // A negative retention pushes the cutoff a day INTO THE FUTURE relative to now, so
        // "createdAt < cutoff" holds with a full day of margin - Duration.ZERO put the cutoff
        // right at "now", leaving no room for any clock-precision/skew wrinkle between when
        // createdAt was stamped and when the job computes cutoff a few DB/S3 round trips later.
        job(Duration.ofDays(-1)).deleteExpiredSources();

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

        assertThatCode(() -> job(Duration.ofDays(-1)).deleteExpiredSources()).doesNotThrowAnyException();

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
        // Garage's metadata index is eventually consistent even single-node (CRDT-based) - a key
        // can be immediately GET/HEAD-able while still briefly missing from ListObjectsV2, which
        // is exactly the API StoragePrefixMover.listKeys() uses. Without waiting for it to show
        // up, deleteExpiredSources() can race: the pre-delete list sees nothing (deletes
        // nothing), the post-delete verification list then DOES see it and throws "cleanup
        // incomplete" - a false failure that has nothing to do with the job's own logic.
        waitUntilListed(sourceKey);
        video.setSourceKey(sourceKey);
        videoRepository.save(video);
        return video;
    }

    private void waitUntilListed(String key) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            boolean visible = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(s3Properties.bucket()).prefix(key).build())
                    .contents().stream()
                    .anyMatch(object -> object.key().equals(key));
            if (visible) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Object '" + key + "' never appeared in listing within 5s");
    }

    private SourceRetentionCleanupJob job(Duration retention) {
        TranscodeProperties properties = new TranscodeProperties("ffmpeg", "ffprobe", null,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(2),
                List.of(Duration.ofMinutes(1)), List.of(360, 720, 1080), retention, 10, 10, 160, 90);
        return new SourceRetentionCleanupJob(videoRepository, storagePrefixMover, properties, clock);
    }
}
