package com.bjarne.videoservice.catalog.repository;

import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.entity.VideoStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    boolean existsBySlug(String slug);

    long countByStatus(VideoStatus status);

    Optional<Video> findBySlug(String slug);

    /**
     * Removes the video row with a single DELETE, bypassing the persistence context. Everything
     * referencing it (transcode_jobs, renditions, upload_sessions, ...) goes via ON DELETE CASCADE
     * in the database - a TranscodeJob still managed in the same session would otherwise make
     * Hibernate refuse the flush (TransientPropertyValueException). The context is cleared
     * afterwards so no stale Video survives in it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Video v where v.id = :id")
    int deleteRowById(@Param("id") UUID id);

    /** Callers pass DELETING as {@code status}: a VIDEO_DELETION job already removes the source. */
    List<Video> findBySourceKeyIsNotNullAndSourceDeletedAtIsNullAndCreatedAtBeforeAndStatusNot(Instant cutoff,
                                                                                               VideoStatus status);

    /**
     * Videos that have storage objects, i.e. everything a CACHE_METADATA_BACKFILL can apply to.
     * Callers pass DELETING as {@code status}: rewriting objects that are about to be deleted is wasted work.
     */
    List<Video> findByStoragePrefixIsNotNullAndStatusNot(VideoStatus status);

    /*
     * cursorTs/cursorId are never null (CatalogService passes an "infinitely far in the future"
     * sentinel when there's no cursor) - a ":cursorTs IS NULL OR ..." branch would prevent
     * Postgres from resolving the parameter type for the plain IS-NULL check
     * (SQLState 42P18, "could not determine data type of parameter").
     */
    @Query("""
            SELECT v FROM Video v
            WHERE v.status = com.bjarne.videoservice.catalog.entity.VideoStatus.READY
              AND v.visibility = com.bjarne.videoservice.catalog.entity.Visibility.PUBLIC
              AND (:categoryId IS NULL OR v.category.id = :categoryId)
              AND (:includeAgeRestricted = true OR v.category.ageRestricted = false)
              AND (v.publishedAt < :cursorTs
                   OR (v.publishedAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.publishedAt DESC, v.id DESC
            """)
    List<Video> findPublicFeed(@Param("categoryId") Long categoryId, @Param("cursorTs") Instant cursorTs,
                                @Param("cursorId") UUID cursorId, @Param("includeAgeRestricted") boolean includeAgeRestricted,
                                Pageable pageable);

    @Query("""
            SELECT v FROM Video v
            WHERE v.user.id = :userId
              AND v.status = com.bjarne.videoservice.catalog.entity.VideoStatus.READY
              AND v.visibility = com.bjarne.videoservice.catalog.entity.Visibility.PUBLIC
              AND (:includeAgeRestricted = true OR v.category.ageRestricted = false)
              AND (v.publishedAt < :cursorTs
                   OR (v.publishedAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.publishedAt DESC, v.id DESC
            """)
    List<Video> findPublicByUser(@Param("userId") UUID userId, @Param("cursorTs") Instant cursorTs,
                                  @Param("cursorId") UUID cursorId, @Param("includeAgeRestricted") boolean includeAgeRestricted,
                                  Pageable pageable);

    /*
     * Native because the trigram operator (<%) and word_similarity() have no JPQL equivalent.
     * A title matches as a substring (pattern is '%q%' with LIKE wildcards already escaped) or,
     * for typos, when word_similarity reaches pg_trgm.word_similarity_threshold (default 0.6).
     * The visibility filter mirrors findPublicFeed.
     */
    @Query(value = """
            SELECT v.* FROM videos v
            JOIN categories c ON c.id = v.category_id
            WHERE v.status = 'READY'
              AND v.visibility = 'PUBLIC'
              AND v.published_at IS NOT NULL
              AND (:includeAgeRestricted OR c.age_restricted = false)
              AND (v.title ILIKE :pattern OR :query <% v.title)
            ORDER BY CASE WHEN :sortByRelevance THEN word_similarity(:query, v.title) END DESC NULLS LAST,
                     v.published_at DESC, v.id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Video> searchPublic(@Param("query") String query, @Param("pattern") String pattern,
                             @Param("includeAgeRestricted") boolean includeAgeRestricted,
                             @Param("sortByRelevance") boolean sortByRelevance,
                             @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = """
            SELECT count(*) FROM videos v
            JOIN categories c ON c.id = v.category_id
            WHERE v.status = 'READY'
              AND v.visibility = 'PUBLIC'
              AND v.published_at IS NOT NULL
              AND (:includeAgeRestricted OR c.age_restricted = false)
              AND (v.title ILIKE :pattern OR :query <% v.title)
            """, nativeQuery = true)
    long countPublicSearch(@Param("query") String query, @Param("pattern") String pattern,
                           @Param("includeAgeRestricted") boolean includeAgeRestricted);

    @Query("""
            SELECT v FROM Video v
            WHERE v.user.id = :userId
              AND v.status <> com.bjarne.videoservice.catalog.entity.VideoStatus.DELETING
              AND (v.createdAt < :cursorTs
                   OR (v.createdAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    List<Video> findByUser(@Param("userId") UUID userId, @Param("cursorTs") Instant cursorTs,
                            @Param("cursorId") UUID cursorId, Pageable pageable);
}
