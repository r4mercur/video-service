package com.bjarne.videoservice.moderation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, Long> {

    boolean existsByVideoIdAndStatus(UUID videoId, ReportStatus status);

    long countByStatus(ReportStatus status);

    /*
     * cursorTs/cursorId are never null (AdminService passes an "infinitely far in the future"
     * sentinel when there's no cursor) - see CatalogService for the same reasoning
     * (Postgres SQLState 42P18 on a bare IS-NULL check).
     */
    @Query("""
            SELECT r FROM Report r
            WHERE (:status IS NULL OR r.status = :status)
              AND (r.createdAt < :cursorTs OR (r.createdAt = :cursorTs AND r.id < :cursorId))
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<Report> findPage(@Param("status") ReportStatus status, @Param("cursorTs") Instant cursorTs,
                           @Param("cursorId") Long cursorId, Pageable pageable);
}
