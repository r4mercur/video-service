package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.Visibility;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.config.ThumbnailProperties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.storage.CachePolicy;
import com.bjarne.videoservice.transcoding.service.FfmpegRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The test CLAUDE.md 9.4 explicitly asks for: it asserts Cache-Control on the PutObjectRequest
 * itself, because that is the only place the value is observable before it reaches the bucket.
 * A thumbnail is replaced in place under a fixed key, so inheriting the segments' year-long
 * immutable policy would leave viewers on the old image with no way to invalidate it - a bug
 * that stays invisible until someone actually changes a thumbnail and waits a year.
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailServiceTest {

    private static final String PREFIX = "public/5c4efbcc-8e86-4686-9090-62de86cd8714";

    @Mock
    private S3Client s3Client;
    @Mock
    private S3BucketInitializer bucketInitializer;
    @Mock
    private FfmpegRunner ffmpegRunner;
    @Mock
    private VideoRepository videoRepository;

    private final S3Properties s3Properties =
            new S3Properties("http://localhost:9000", "us-east-1", "video-service-test", "key", "secret",
                    true, List.of(), "http://localhost:9000");

    @Test
    void customThumbnailIsUploadedWithTheShortCachePolicyNotTheSegmentPolicy() {
        ThumbnailService service = new ThumbnailService(s3Client, s3Properties, bucketInitializer,
                transcodeProperties(), new ThumbnailProperties(8 * 1024 * 1024), ffmpegRunner, videoRepository,
                new CachePolicy());
        Video video = new Video(null, null, "A video", "a-video", Visibility.PUBLIC);
        video.setStoragePrefix(PREFIX);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.store(video, new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[] {1, 2, 3}));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        org.mockito.Mockito.verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo(PREFIX + "/thumbnail_custom.jpg");
        assertThat(request.getValue().cacheControl())
                .isEqualTo(CachePolicy.SHORT_LIVED)
                .isNotEqualTo(CachePolicy.IMMUTABLE);
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
    }

    private static TranscodeProperties transcodeProperties() {
        return new TranscodeProperties("ffmpeg", "ffprobe", null, Duration.ofHours(2), Duration.ofSeconds(5),
                Duration.ofHours(2), List.of(Duration.ofMinutes(1)), List.of(360, 720, 1080),
                Duration.ofDays(30), 10, 10, 160, 90);
    }
}
