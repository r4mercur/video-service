package com.bjarne.videoservice.catalog.storage;

import com.bjarne.videoservice.config.S3BucketInitializer;
import com.bjarne.videoservice.config.S3Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the video-deletion bug found 2026-08-31: deleteAll(prefix) used to
 * treat an empty listObjectsV2 result as "nothing left to do" and let the caller
 * (VideoManagementService.delete, @Transactional) go on to remove the video row even when the
 * delete actually left objects behind - the DB record was gone, the S3 objects stayed forever
 * orphaned. deleteAll(String) now re-lists the prefix after deleting and throws if it isn't
 * actually empty, so the transaction rolls back instead of silently losing the storage
 * reference. move() (used for visibility changes, CLAUDE.md 9.5) had the same gap in its
 * old-prefix cleanup and now goes through the same verified deleteAll(String) instead of the
 * unverified deleteAll(List) it used to call directly.
 */
@ExtendWith(MockitoExtension.class)
class StoragePrefixMoverTest {

    private static final String PREFIX = "public/5c4edc5e-e24e-4917-85a5-276cb038f4fb";
    private static final String OLD_PREFIX = "private/5c4edc5e-e24e-4917-85a5-276cb038f4fb";
    private static final String NEW_PREFIX = "public/5c4edc5e-e24e-4917-85a5-276cb038f4fb";
    private static final String BUCKET = "video-service-test";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3BucketInitializer bucketInitializer;

    private final S3Properties properties =
            new S3Properties("http://localhost:9000", "us-east-1", BUCKET, "key", "secret", true, List.of(),
                    "http://localhost:9000");

    @Test
    void deleteAllThrowsWhenObjectsRemainAfterDelete() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);

        // Every listObjectsV2 call - before and after the delete - still reports the same
        // object, simulating a delete that silently didn't clear the prefix (e.g. a listing
        // that lagged behind a just-finished multi-object write).
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(listResponseWith(PREFIX + "/360p/segment_000.m4s"));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        assertThatIllegalStateException()
                .isThrownBy(() -> mover.deleteAll(PREFIX))
                .withMessageContaining(PREFIX);

        // The delete attempt itself must still have happened - the failure is in the
        // verification, not a reason to skip trying.
        verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void deleteAllSucceedsWhenPrefixIsActuallyEmptyAfterDelete() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);

        // First call (before delete) finds the object, second call (the new verification
        // re-list) confirms it is really gone.
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        listResponseWith(PREFIX + "/360p/segment_000.m4s"),
                        listResponseWith());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        assertThatCode(() -> mover.deleteAll(PREFIX)).doesNotThrowAnyException();

        verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void deleteAllIsANoOpWhenPrefixWasAlreadyEmpty() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponseWith());

        assertThatCode(() -> mover.deleteAll(PREFIX)).doesNotThrowAnyException();

        verify(s3Client, times(0)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void moveIsANoOpWhenOldAndNewPrefixAreEqual() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);

        mover.move(OLD_PREFIX, OLD_PREFIX);

        verifyNoInteractions(s3Client, bucketInitializer);
    }

    @Test
    void moveCopiesThenDeletesAndVerifiesOldPrefixIsActuallyEmpty() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);
        String oldKey = OLD_PREFIX + "/360p/segment_000.m4s";

        // 1st call: listKeys(oldPrefix) in move() itself, to drive the copy loop.
        // 2nd call: listKeys(oldPrefix) inside deleteAll(oldPrefix), before deleting.
        // 3rd call: deleteAll's own re-list, confirming the old prefix is now really empty.
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(listResponseWith(oldKey), listResponseWith(oldKey), listResponseWith());
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        assertThatCode(() -> mover.move(OLD_PREFIX, NEW_PREFIX)).doesNotThrowAnyException();

        verify(s3Client).copyObject(CopyObjectRequest.builder()
                .sourceBucket(BUCKET).sourceKey(oldKey)
                .destinationBucket(BUCKET).destinationKey(NEW_PREFIX + "/360p/segment_000.m4s")
                .build());
        verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void moveThrowsWhenOldPrefixStillHasObjectsAfterCopyAndDelete() {
        StoragePrefixMover mover = new StoragePrefixMover(s3Client, properties, bucketInitializer);
        String oldKey = OLD_PREFIX + "/360p/segment_000.m4s";

        // Every listing, including deleteAll's post-delete verification, still finds the
        // object - the copy succeeded, but the old prefix was never actually cleared.
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponseWith(oldKey));
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        assertThatIllegalStateException()
                .isThrownBy(() -> mover.move(OLD_PREFIX, NEW_PREFIX))
                .withMessageContaining(OLD_PREFIX);

        // The copy must have gone through regardless - only the cleanup verification failed.
        verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
    }

    private static ListObjectsV2Response listResponseWith(String... keys) {
        List<S3Object> objects = List.of(keys).stream()
                .map(key -> S3Object.builder().key(key).build())
                .toList();
        return ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();
    }
}
