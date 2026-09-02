package com.bjarne.videoservice.moderation.repository;

import com.bjarne.videoservice.moderation.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
