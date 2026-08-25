package com.bjarne.videoservice.transcoding;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TranscodeJobRepository extends JpaRepository<TranscodeJob, Long> {

    Optional<TranscodeJob> findFirstByVideoIdOrderByCreatedAtDesc(UUID videoId);

    List<TranscodeJob> findByStatusAndLockedAtBefore(JobStatus status, Instant cutoff);

    /**
     * SKIP LOCKED (Postgres) via Hibernate's lock.timeout=-2: multiple worker instances can
     * poll in parallel without blocking each other or claiming the same job.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from TranscodeJob j where j.status = :status and j.scheduledAt <= :now order by j.scheduledAt asc")
    List<TranscodeJob> findClaimable(@Param("status") JobStatus status, @Param("now") Instant now, Pageable pageable);
}
