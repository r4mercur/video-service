package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Bewegt bzw. loescht alle Objekte eines Videos im Storage (CLAUDE.md 9.3: Sichtbarkeitswechsel
 * verschiebt zwischen public/private-Prefixes; DELETE raeumt komplett auf). S3 kennt kein "move",
 * daher Copy je Objekt und erst danach Batch-Delete der alten Keys - schlaegt ein Copy fehl,
 * bleiben die alten Objekte unangetastet, es geht nichts verloren.
 */
@Component
public class StoragePrefixMover {

    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client s3Client;
    private final S3Properties properties;
    private final S3BucketInitializer bucketInitializer;

    public StoragePrefixMover(S3Client s3Client, S3Properties properties, S3BucketInitializer bucketInitializer) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.bucketInitializer = bucketInitializer;
    }

    public static String rewriteKey(String key, String oldPrefix, String newPrefix) {
        return key == null ? null : newPrefix + key.substring(oldPrefix.length());
    }

    public void move(String oldPrefix, String newPrefix) {
        if (oldPrefix.equals(newPrefix)) {
            return;
        }
        bucketInitializer.ensureReady();
        List<String> oldKeys = listKeys(oldPrefix);
        for (String oldKey : oldKeys) {
            String newKey = rewriteKey(oldKey, oldPrefix, newPrefix);
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(properties.bucket()).sourceKey(oldKey)
                    .destinationBucket(properties.bucket()).destinationKey(newKey)
                    .build());
        }
        deleteAll(oldKeys);
    }

    public void deleteAll(String prefix) {
        bucketInitializer.ensureReady();
        deleteAll(listKeys(prefix));
    }

    private void deleteAll(List<String> keys) {
        for (int i = 0; i < keys.size(); i += DELETE_BATCH_SIZE) {
            List<ObjectIdentifier> batch = keys.subList(i, Math.min(i + DELETE_BATCH_SIZE, keys.size())).stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();
            if (batch.isEmpty()) {
                continue;
            }
            s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(properties.bucket())
                    .delete(Delete.builder().objects(batch).build())
                    .build());
        }
    }

    private List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(properties.bucket())
                    .prefix(prefix + "/")
                    .continuationToken(continuationToken)
                    .build());
            for (S3Object object : response.contents()) {
                keys.add(object.key());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }
}
