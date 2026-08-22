package com.bjarne.videoservice.upload;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.net.URL;
import java.time.Duration;
import java.util.List;

/**
 * Duenner Wrapper um S3Client/S3Presigner fuer den Multipart-Upload-Flow.
 * Kein Video-Byte laeuft durch Spring MVC (CLAUDE.md 3.2) - der Browser laedt Parts direkt hier hoch.
 * Bucket/CORS-Bootstrap siehe {@link S3BucketInitializer}.
 */
@Component
public class S3MultipartClient {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties properties;
    private final S3BucketInitializer bucketInitializer;

    public S3MultipartClient(S3Client s3Client, S3Presigner s3Presigner, S3Properties properties,
                              S3BucketInitializer bucketInitializer) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
        this.bucketInitializer = bucketInitializer;
    }

    public String createMultipartUpload(String key, String contentType) {
        bucketInitializer.ensureReady();
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build()).uploadId();
    }

    public URL presignPartUrl(String key, String uploadId, int partNumber, Duration ttl) {
        PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(UploadPartRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .build())
                .build());
        return presigned.url();
    }

    public void completeMultipartUpload(String key, String uploadId, List<CompletedPartDto> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());
    }

    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (NoSuchUploadException e) {
            // bereits abgebrochen/abgeschlossen - Ziel bereits erreicht
        }
    }

    public long headObjectSize(String key) {
        return s3Client.headObject(HeadObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build()).contentLength();
    }
}
