package com.bjarne.videoservice.transcoding.repository;

import com.bjarne.videoservice.transcoding.entity.JobStatus;
import com.bjarne.videoservice.transcoding.entity.JobType;
import com.bjarne.videoservice.transcoding.entity.TranscodeJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TranscodeJobRepository extends JpaRepository<TranscodeJob, Long> {

    Optional<TranscodeJob> findFirstByVideoIdOrderByCreatedAtDesc(UUID videoId);

    long countByStatus(JobStatus status);

    long countByStatusAndType(JobStatus status, JobType type);

    @Query("select min(j.scheduledAt) from TranscodeJob j where j.status = :status")
    Optional<Instant> findOldestScheduledAt(@Param("status") JobStatus status);

    List<TranscodeJob> findByStatusAndLockedAtBefore(JobStatus status, Instant cutoff);

    boolean existsByVideoIdAndTypeAndStatusIn(UUID videoId, JobType type, List<JobStatus> statuses);

    boolean existsByVideoIdAndTypeInAndStatusIn(UUID videoId, Collection<JobType> types, Collection<JobStatus> statuses);

    void deleteByVideoIdAndTypeAndStatus(UUID videoId, JobType type, JobStatus status);

    /**
     * SKIP LOCKED (Postgres) via Hibernate's lock.timeout=-2: multiple worker instances can
     * poll in parallel without blocking each other or claiming the same job.
     *
     * <p>VIDEO_DELETION jobs are claimed before everything else, oldest first within each group:
     * with a single worker (CLAUDE.md 9.2) a deletion would otherwise queue behind transcodes
     * that can each take hours, while storage the user asked to be removed stays around.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select j from TranscodeJob j
            where j.status = :status and j.scheduledAt <= :now
            order by case when j.type = com.bjarne.videoservice.transcoding.entity.JobType.VIDEO_DELETION then 0 else 1 end,
                     j.scheduledAt asc
            """)
    List<TranscodeJob> findClaimable(@Param("status") JobStatus status, @Param("now") Instant now, Pageable pageable);
}
