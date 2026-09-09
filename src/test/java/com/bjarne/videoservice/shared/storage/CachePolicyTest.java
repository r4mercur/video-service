package com.bjarne.videoservice.shared.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * CLAUDE.md 9.4 calls the thumbnail/segment cache distinction "the single easiest place in the
 * codebase to introduce a bug that is invisible for a year" and asks for a test on it. Hence the
 * emphasis here on what must *not* be immutable, rather than only on the happy path.
 */
class CachePolicyTest {

    private static final String PUBLIC_PREFIX = "public/5c4efbcc-8e86-4686-9090-62de86cd8714";
    private static final String PRIVATE_PREFIX = "private/5c4efbcc-8e86-4686-9090-62de86cd8714";

    private final CachePolicy policy = new CachePolicy();

    @Test
    void publicRenditionArtifactsAreImmutable() {
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/360p/segment_000.m4s")).isEqualTo(CachePolicy.IMMUTABLE);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/720p/segment_417.m4s")).isEqualTo(CachePolicy.IMMUTABLE);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/1080p/init.mp4")).isEqualTo(CachePolicy.IMMUTABLE);
    }

    /**
     * These all change in place under an unchanged key, so a year-long immutable cache would
     * strand viewers on a stale copy with no way to invalidate it.
     */
    @Test
    void mutableArtifactsGetTheShortPolicyNeverImmutable() {
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/thumbnail.jpg")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/thumbnail_custom.jpg")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/sprite.jpg")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/master.m3u8")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/360p/playlist.m3u8")).isEqualTo(CachePolicy.SHORT_LIVED);
    }

    /**
     * The specific regression CLAUDE.md 9.4 warns about: a custom thumbnail sits in the same
     * prefix as the segments and differs only by extension.
     */
    @Test
    void customThumbnailNextToImmutableSegmentsIsNotImmutable() {
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/360p/segment_000.m4s"))
                .isEqualTo(CachePolicy.IMMUTABLE);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/thumbnail_custom.jpg"))
                .isEqualTo(CachePolicy.SHORT_LIVED)
                .isNotEqualTo(CachePolicy.IMMUTABLE);
    }

    /**
     * Private objects reach the browser only through presigned URLs with a 3 h expiry
     * (CLAUDE.md 9.3). Caching them past that would outlive the guard.
     */
    @Test
    void everythingUnderThePrivatePrefixIsNoStore() {
        assertThat(policy.cacheControlFor(PRIVATE_PREFIX + "/360p/segment_000.m4s")).isEqualTo(CachePolicy.NO_STORE);
        assertThat(policy.cacheControlFor(PRIVATE_PREFIX + "/360p/init.mp4")).isEqualTo(CachePolicy.NO_STORE);
        assertThat(policy.cacheControlFor(PRIVATE_PREFIX + "/master.m3u8")).isEqualTo(CachePolicy.NO_STORE);
        assertThat(policy.cacheControlFor(PRIVATE_PREFIX + "/thumbnail.jpg")).isEqualTo(CachePolicy.NO_STORE);
    }

    @Test
    void retainedSourceUploadIsNeverCacheable() {
        assertThat(policy.cacheControlFor("source/5c4efbcc-8e86-4686-9090-62de86cd8714/source.mp4"))
                .isEqualTo(CachePolicy.NO_STORE);
    }

    /**
     * An extension nobody anticipated must fall back to the short policy. Defaulting to
     * IMMUTABLE would make every future artifact type a year-long mistake.
     */
    @Test
    void unknownArtifactsFallBackToTheShortPolicy() {
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/subtitles.vtt")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/something.bin")).isEqualTo(CachePolicy.SHORT_LIVED);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/no-extension")).isEqualTo(CachePolicy.SHORT_LIVED);
    }

    /**
     * "init.mp4" is matched as a whole path segment, not as a suffix - a key merely *ending* in
     * those characters must not inherit the init segment's immutability.
     */
    @Test
    void onlyTheHlsInitSegmentMatchesTheInitRule() {
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/720p/init.mp4")).isEqualTo(CachePolicy.IMMUTABLE);
        assertThat(policy.cacheControlFor(PUBLIC_PREFIX + "/720p/reinit.mp4")).isEqualTo(CachePolicy.SHORT_LIVED);
    }

    @Test
    void rejectsNullKeyRatherThanGuessingAPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy.cacheControlFor(null));
    }
}
