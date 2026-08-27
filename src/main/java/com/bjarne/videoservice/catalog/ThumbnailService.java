package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.config.ThumbnailProperties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
import com.bjarne.videoservice.transcoding.FfmpegRunner;
import com.bjarne.videoservice.transcoding.TranscodeProcessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Lets the owner replace the auto-generated thumbnail (HlsPackager.createThumbnail) with their
 * own image. Stored under a separate key ("thumbnail_custom.jpg") next to the auto-generated one
 * ("thumbnail.jpg", CLAUDE.md 9.2) so a later re-transcode can regenerate the latter without
 * touching an active custom thumbnail (see TranscodeJobLifecycle.recordSuccess).
 *
 * <p>The upload is a normal MultipartFile handled directly in the api process (not a presigned S3
 * multipart upload like video uploads): a thumbnail is a few MB, hard-capped, so the concern
 * behind "never stream video bytes through Spring MVC" (blocking a request thread for a
 * multi-hour transcode, CLAUDE.md 3.2) does not apply here. For the same reason, normalizing the
 * image via ffmpeg happens synchronously in this request rather than via the worker job queue -
 * a single-frame resize takes well under a second.
 */
@Component
public class ThumbnailService {

    private static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(30);

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final S3BucketInitializer bucketInitializer;
    private final TranscodeProperties transcodeProperties;
    private final ThumbnailProperties thumbnailProperties;
    private final FfmpegRunner ffmpegRunner;
    private final VideoRepository videoRepository;

    public ThumbnailService(S3Client s3Client, S3Properties s3Properties, S3BucketInitializer bucketInitializer,
                             TranscodeProperties transcodeProperties, ThumbnailProperties thumbnailProperties,
                             FfmpegRunner ffmpegRunner, VideoRepository videoRepository) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
        this.bucketInitializer = bucketInitializer;
        this.transcodeProperties = transcodeProperties;
        this.thumbnailProperties = thumbnailProperties;
        this.ffmpegRunner = ffmpegRunner;
        this.videoRepository = videoRepository;
    }

    @Transactional
    public void store(Video video, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("Thumbnail file must not be empty");
        }
        if (file.getSize() > thumbnailProperties.maxSizeBytes()) {
            throw new ValidationException("Thumbnail exceeds maximum size of " + thumbnailProperties.maxSizeBytes()
                    + " bytes");
        }

        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("thumbnail-in-", ".upload");
            file.transferTo(input);
            output = Files.createTempFile("thumbnail-out-", ".jpg");

            normalize(input, output);

            String key = video.getStoragePrefix() + "/thumbnail_custom.jpg";
            bucketInitializer.ensureReady();
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .contentType("image/jpeg")
                    .build(), RequestBody.fromFile(output));

            video.setThumbnailKey(key);
            video.setHasCustomThumbnail(true);
            videoRepository.save(video);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not process uploaded thumbnail", e);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    @Transactional
    public void remove(Video video) {
        if (video.isHasCustomThumbnail()) {
            bucketInitializer.ensureReady();
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(video.getStoragePrefix() + "/thumbnail_custom.jpg")
                    .build());
        }
        video.setThumbnailKey(video.getStoragePrefix() + "/thumbnail.jpg");
        video.setHasCustomThumbnail(false);
        videoRepository.save(video);
    }

    private void normalize(Path input, Path output) {
        try {
            ffmpegRunner.run(List.of(
                    transcodeProperties.ffmpegPath(), "-y",
                    "-i", input.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=640:-2",
                    output.toString()
            ), FFMPEG_TIMEOUT);
        } catch (TranscodeProcessException e) {
            throw new ValidationException("Uploaded file is not a valid image");
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort temp file cleanup
        }
    }
}
