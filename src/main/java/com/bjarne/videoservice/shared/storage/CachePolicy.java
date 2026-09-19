package com.bjarne.videoservice.shared.storage;

import org.springframework.stereotype.Component;

/**
 * Maps a storage key to the {@code Cache-Control} value its object must carry (CLAUDE.md 9.3).
 *
 * Since Caddy is not in the media data path in production, no proxy downstream adds these
 * headers - they exist only if they are written as object metadata at PutObject time. Every
 * component that writes an object goes through here: {@code ArtifactStorage} (transcode output),
 * {@code ThumbnailService} (custom thumbnails), {@code AvatarStorage} (profile photos) and
 * {@code StoragePrefixMover} (visibility
 * migration, which must re-derive the value because the correct policy depends on the prefix the
 * object is moving *to*).
 *
 * Lives in shared/ rather than in one feature's storage/ because all three callers sit in
 * different features; CLAUDE.md 9.3 asks for it "inside storage/", which the sub-package honours
 * without picking an arbitrary owning feature (and keeps it importable once Spring Modulith
 * lands, CLAUDE.md 5).
 *
 * ⚠ The default is deliberately the *short* policy, never {@link #IMMUTABLE}. An unknown key
 * getting a five-minute cache is a rounding error; an unknown key getting a year-long immutable
 * cache is unfixable for a year (CLAUDE.md 9.4). Only extensions that are genuinely
 * write-once-never-modified opt into IMMUTABLE below.
 */
@Component
public class CachePolicy {

    /**
     * Renditions are written once under a key that contains the video id and never rewritten -
     * a re-transcode produces a new prefix. Safe to pin for a year.
     */
    public static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /**
     * Playlists and images can change in place under the same key: a thumbnail is replaced by
     * {@code PUT /api/videos/{id}/thumbnail}, a master playlist is rewritten when the ladder
     * changes. Five minutes bounds how long a stale copy can survive (CLAUDE.md 9.3/9.4).
     */
    public static final String SHORT_LIVED = "public, max-age=300";

    /**
     * Anything under {@code private/} or {@code source/}. Private segments reach the browser via
     * presigned URLs whose whole point is a 3 h expiry - letting a shared cache keep the bytes
     * after the URL dies would defeat that (CLAUDE.md 9.3). Sources are never served at all.
     */
    public static final String NO_STORE = "no-store";

    /** Storage prefix of all profile photos - under public/ so the bucket policy makes them readable. */
    public static final String AVATAR_PREFIX = "public/avatars/";

    public String cacheControlFor(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Storage key must not be null");
        }
        if (key.startsWith("private/") || key.startsWith("source/")) {
            return NO_STORE;
        }
        return isImmutableMediaSegment(key) || isAvatar(key) ? IMMUTABLE : SHORT_LIVED;
    }

    /**
     * A profile photo is user-replaceable like a custom thumbnail, but it is never replaced in
     * place: every upload gets a fresh {@code public/avatars/{userId}/{uuid}.jpg} key (see
     * AvatarStorage#newKey) and the old object is deleted. A key that is never rewritten is safe to
     * pin for a year - and it is the only way a replaced photo shows up immediately instead of
     * after SHORT_LIVED's five minutes (CLAUDE.md 9.8). This rule is only correct as long as
     * that key scheme holds; it must never be applied to {@code thumbnail*.jpg}.
     */
    private boolean isAvatar(String key) {
        return key.startsWith(AVATAR_PREFIX);
    }

    /**
     * HLS fMP4 output is exactly two shapes: numbered {@code *.m4s} media segments and the one
     * {@code init.mp4} initialisation segment per rendition (see HlsPackager's
     * -hls_fmp4_init_filename). Matching {@code init.mp4} by name rather than {@code *.mp4} in
     * general keeps the uploaded source file - also an .mp4 - out of the immutable bucket even
     * if it ever moves out from under the source/ prefix. If the init filename is ever changed
     * in HlsPackager, this falls back to SHORT_LIVED: a small performance regression, never a
     * stale-forever object.
     */
    private boolean isImmutableMediaSegment(String key) {
        return key.endsWith(".m4s") || key.endsWith("/init.mp4");
    }
}
