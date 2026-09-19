package com.bjarne.videoservice.identity.service;

import com.bjarne.videoservice.config.AvatarProperties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.identity.storage.AvatarStorage;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
import com.bjarne.videoservice.transcoding.service.FfmpegRunner;
import com.bjarne.videoservice.transcoding.service.TranscodeProcessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Profile photos (CLAUDE.md 9.8). Same shape as ThumbnailService: a small, hard-capped
 * MultipartFile handled in the api process and normalized synchronously with ffmpeg - a single
 * frame, well under a second, so the "never MultipartFile" rule for video bytes (CLAUDE.md 3.2)
 * does not apply.
 *
 * <p>The image is center-cropped to a square and scaled to a fixed size, so the frontend never has
 * to deal with arbitrary aspect ratios and the stored object is a few KB regardless of the upload.
 *
 * <p>The previous object is deleted only after the transaction commits: deleting it earlier and
 * then rolling back would leave the row pointing at an object that no longer exists.
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final AvatarStorage avatarStorage;
    private final FfmpegRunner ffmpegRunner;
    private final TranscodeProperties transcodeProperties;
    private final AvatarProperties avatarProperties;

    public AvatarService(UserRepository userRepository,
                         AvatarStorage avatarStorage,
                         FfmpegRunner ffmpegRunner,
                         TranscodeProperties transcodeProperties,
                         AvatarProperties avatarProperties) {
        this.userRepository = userRepository;
        this.avatarStorage = avatarStorage;
        this.ffmpegRunner = ffmpegRunner;
        this.transcodeProperties = transcodeProperties;
        this.avatarProperties = avatarProperties;
    }

    @Transactional
    public User store(UUID userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("Profile photo must not be empty");
        }
        if (file.getSize() > avatarProperties.maxSizeBytes()) {
            throw new ValidationException("Profile photo exceeds maximum size of " + avatarProperties.maxSizeBytes()
                    + " bytes");
        }
        User user = requireUser(userId);

        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("avatar-in-", ".upload");
            file.transferTo(input);
            output = Files.createTempFile("avatar-out-", ".jpg");

            normalize(input, output);

            String key = avatarStorage.newKey(userId);
            avatarStorage.put(key, output);

            String previousKey = user.getAvatarKey();
            user.setAvatarKey(key);
            userRepository.save(user);
            deleteAfterCommit(previousKey);
            return user;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not process uploaded profile photo", e);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    /**
     * Idempotent: removing a photo that isn't there is not an error for the owner. The admin path
     * (AdminService#removeUserAvatar) checks for that case itself, because an audit entry for a
     * no-op would be misleading.
     */
    @Transactional
    public User remove(UUID userId) {
        User user = requireUser(userId);
        String previousKey = user.getAvatarKey();
        if (previousKey != null) {
            user.setAvatarKey(null);
            userRepository.save(user);
            deleteAfterCommit(previousKey);
        }
        return user;
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private void normalize(Path input, Path output) {
        int size = avatarProperties.sizePixels();
        try {
            ffmpegRunner.run(List.of(
                    transcodeProperties.ffmpegPath(), "-y",
                    "-format_whitelist", FfmpegRunner.STILL_IMAGE_INPUT_FORMATS,
                    "-i", input.toString(),
                    "-frames:v", "1",
                    "-vf", "crop='min(iw,ih)':'min(iw,ih)',scale=" + size + ":" + size,
                    output.toString()
            ), FFMPEG_TIMEOUT);
        } catch (TranscodeProcessException e) {
            throw new ValidationException("Uploaded file is not a valid image");
        }
    }

    /**
     * Runs after commit when called inside a transaction (the normal case), immediately otherwise.
     * A failed delete only leaves an unreferenced object under the user's own prefix - logged, not
     * rethrown, because the user-visible change has already been committed.
     */
    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteObjectQuietly(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteObjectQuietly(key);
            }
        });
    }

    private void deleteObjectQuietly(String key) {
        try {
            avatarStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete replaced profile photo {} - left as an orphan under the user's prefix", key, e);
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
