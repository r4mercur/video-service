package com.bjarne.videoservice.upload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    Optional<UploadSession> findFirstByVideoIdAndCompletedAtIsNullOrderByIdDesc(UUID videoId);

    List<UploadSession> findByExpiresAtBeforeAndCompletedAtIsNull(Instant cutoff);
}
