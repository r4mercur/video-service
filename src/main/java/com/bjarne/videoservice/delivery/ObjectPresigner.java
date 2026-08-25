package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.config.DeliveryProperties;
import com.bjarne.videoservice.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Presigned GetObject URLs for objects of private videos (CLAUDE.md 9.3, TTL 3h) - HLS
 * segments/init files (see {@link PlaylistRewriter}) as well as thumbnail/sprite sheet (see
 * {@link MediaUrlResolver}). This lets the browser load the bytes directly from storage,
 * not through the backend.
 */
@Component
public class ObjectPresigner {

    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;
    private final DeliveryProperties deliveryProperties;

    public ObjectPresigner(S3Presigner s3Presigner, S3Properties s3Properties, DeliveryProperties deliveryProperties) {
        this.s3Presigner = s3Presigner;
        this.s3Properties = s3Properties;
        this.deliveryProperties = deliveryProperties;
    }

    public String presignGet(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(deliveryProperties.segmentUrlTtl())
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(key)
                        .build())
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }
}
