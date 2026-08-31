package com.bjarne.videoservice.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    boolean existsBySlug(String slug);

    Optional<Video> findBySlug(String slug);

    List<Video> findBySourceKeyIsNotNullAndSourceDeletedAtIsNullAndCreatedAtBefore(Instant cutoff);

    /*
     * cursorTs/cursorId are never null (CatalogService passes an "infinitely far in the future"
     * sentinel when there's no cursor) - a ":cursorTs IS NULL OR ..." branch would prevent
     * Postgres from resolving the parameter type for the plain IS-NULL check
     * (SQLState 42P18, "could not determine data type of parameter").
     */
    @Query("""
            SELECT v FROM Video v
            WHERE v.status = com.bjarne.videoservice.catalog.VideoStatus.READY
              AND v.visibility = com.bjarne.videoservice.catalog.Visibility.PUBLIC
              AND (:categoryId IS NULL OR v.category.id = :categoryId)
              AND (v.publishedAt < :cursorTs
                   OR (v.publishedAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.publishedAt DESC, v.id DESC
            """)
    List<Video> findPublicFeed(@Param("categoryId") Long categoryId, @Param("cursorTs") Instant cursorTs,
                                @Param("cursorId") UUID cursorId, Pageable pageable);

    @Query("""
            SELECT v FROM Video v
            WHERE v.user.id = :userId
              AND v.status = com.bjarne.videoservice.catalog.VideoStatus.READY
              AND v.visibility = com.bjarne.videoservice.catalog.Visibility.PUBLIC
              AND (v.publishedAt < :cursorTs
                   OR (v.publishedAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.publishedAt DESC, v.id DESC
            """)
    List<Video> findPublicByUser(@Param("userId") UUID userId, @Param("cursorTs") Instant cursorTs,
                                  @Param("cursorId") UUID cursorId, Pageable pageable);

    @Query("""
            SELECT v FROM Video v
            WHERE v.user.id = :userId
              AND (v.createdAt < :cursorTs
                   OR (v.createdAt = :cursorTs AND v.id < :cursorId))
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    List<Video> findByUser(@Param("userId") UUID userId, @Param("cursorTs") Instant cursorTs,
                            @Param("cursorId") UUID cursorId, Pageable pageable);
}
