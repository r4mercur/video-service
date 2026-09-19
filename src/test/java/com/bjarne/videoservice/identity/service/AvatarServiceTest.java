package com.bjarne.videoservice.identity.service;

import com.bjarne.videoservice.config.AvatarProperties;
import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.identity.storage.AvatarStorage;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Asserts Cache-Control on the PutObjectRequest itself, like ThumbnailServiceTest does for
 * thumbnails (CLAUDE.md 9.4): the immutable policy is only correct because every upload gets a
 * fresh key, so both halves - the header and the key scheme - are checked together here.
 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    private static final UUID USER_ID = UUID.fromString("5c4efbcc-8e86-4686-9090-62de86cd8714");

    @Mock
    private S3Client s3Client;
    @Mock
    private S3BucketInitializer bucketInitializer;
    @Mock
    private FfmpegRunner ffmpegRunner;
    @Mock
    private UserRepository userRepository;

    private final S3Properties s3Properties =
            new S3Properties("http://localhost:9000", "us-east-1", "video-service-test", "key", "secret",
                    true, List.of(), "http://localhost:9000");

    @Test
    void avatarIsUploadedUnderAFreshKeyWithTheImmutablePolicy() {
        User user = user(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service().store(USER_ID, jpeg());

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key())
                .matches("public/avatars/" + USER_ID + "/[0-9a-f-]{36}\\.jpg");
        assertThat(request.getValue().cacheControl()).isEqualTo(CachePolicy.IMMUTABLE);
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(user.getAvatarKey()).isEqualTo(request.getValue().key());
    }

    /** Immutable caching breaks the moment a key is reused - a replacement must never overwrite. */
    @Test
    void replacingAnAvatarWritesANewKeyAndDeletesTheOldObject() {
        String oldKey = "public/avatars/" + USER_ID + "/0f8fad5b-d9cb-469f-a165-70867728950e.jpg";
        User user = user(oldKey);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service().store(USER_ID, jpeg());

        assertThat(user.getAvatarKey()).isNotEqualTo(oldKey);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(oldKey);
    }

    /** The input format whitelist is what keeps ffmpeg from following HLS/concat references. */
    @Test
    @SuppressWarnings("unchecked")
    void ffmpegIsRestrictedToStillImageInputFormats() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service().store(USER_ID, jpeg());

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(ffmpegRunner).run(command.capture(), any(Duration.class));
        List<String> args = command.getValue();
        int whitelist = args.indexOf("-format_whitelist");
        assertThat(whitelist).isNotNegative().isLessThan(args.indexOf("-i"));
        assertThat(args.get(whitelist + 1)).doesNotContain("hls", "concat");
    }

    @Test
    void oversizedUploadIsRejectedBeforeFfmpegRuns() {
        byte[] tooLarge = new byte[1025];

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service(1024).store(USER_ID,
                        new MockMultipartFile("file", "big.jpg", "image/jpeg", tooLarge)));

        verify(ffmpegRunner, never()).run(anyList(), any(Duration.class));
        verifyNoInteractions(s3Client);
    }

    private AvatarService service() {
        return service(5 * 1024 * 1024);
    }

    private AvatarService service(long maxSizeBytes) {
        AvatarStorage storage = new AvatarStorage(s3Client, s3Properties, bucketInitializer, new CachePolicy());
        return new AvatarService(userRepository, storage, ffmpegRunner, transcodeProperties(),
                new AvatarProperties(maxSizeBytes, 256));
    }

    private static User user(String avatarKey) {
        User user = new User("avatar@example.com", "avatar-user", "irrelevant-hash");
        user.setAvatarKey(avatarKey);
        return user;
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "me.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private static TranscodeProperties transcodeProperties() {
        return new TranscodeProperties("ffmpeg", "ffprobe", null, Duration.ofHours(2), Duration.ofSeconds(5),
                Duration.ofHours(2), List.of(Duration.ofMinutes(1)), List.of(360, 720, 1080),
                Duration.ofDays(30), 10, 10, 160, 90);
    }
}
