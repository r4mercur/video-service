package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads the locally generated transcoding artifacts (renditions, master playlist, thumbnail,
 * sprite sheet) to S3. Deliberately independent of the upload package (no multipart needed,
 * the files are small) - avoids coupling transcoding -> upload.
 */
@Component
public class ArtifactStorage {

    private final S3Client s3Client;
    private final S3Properties properties;
    private final S3BucketInitializer bucketInitializer;

    public ArtifactStorage(S3Client s3Client, S3Properties properties, S3BucketInitializer bucketInitializer) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.bucketInitializer = bucketInitializer;
    }

    public void downloadObject(String key, Path destination) {
        bucketInitializer.ensureReady();
        s3Client.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build(), destination);
    }

    public void uploadDirectory(Path localBaseDir, String storagePrefix) {
        bucketInitializer.ensureReady();
        try (var files = Files.walk(localBaseDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String relative = localBaseDir.relativize(file).toString().replace('\\', '/');
                upload(storagePrefix + "/" + relative, file);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void upload(String key, Path file) {
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentTypeFor(file))
                .build(), RequestBody.fromFile(file));
    }

    private String contentTypeFor(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (name.endsWith(".m4s") || name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".jpg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
