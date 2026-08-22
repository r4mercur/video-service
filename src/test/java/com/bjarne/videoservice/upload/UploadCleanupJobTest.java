package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.catalog.Category;
import com.bjarne.videoservice.catalog.CategoryRepository;
import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.VideoStatus;
import com.bjarne.videoservice.catalog.Visibility;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractS3IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class UploadCleanupJobTest extends AbstractS3IntegrationTest {

    @Autowired
    private UploadCleanupJob uploadCleanupJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private S3MultipartClient s3MultipartClient;

    @Test
    void abortsExpiredSessionAndMarksVideoFailed() {
        User user = userRepository.save(new User("cleanup-" + UUID.randomUUID() + "@example.com",
                "cleanup-" + UUID.randomUUID(), "irrelevant-hash"));
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();
        Video video = new Video(user, category, "Orphaned Upload", "orphaned-" + UUID.randomUUID(), Visibility.PUBLIC);
        videoRepository.save(video);

        String key = "public/" + video.getId() + "/source.mp4";
        String uploadId = s3MultipartClient.createMultipartUpload(key, "video/mp4");
        UploadSession session = new UploadSession(video, uploadId, key, Instant.now().minus(1, ChronoUnit.DAYS));
        uploadSessionRepository.save(session);

        uploadCleanupJob.abortExpiredSessions();

        Video reloadedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloadedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);

        UploadSession reloadedSession = uploadSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloadedSession.getCompletedAt()).isNotNull();
    }
}
