package com.bjarne.videoservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    boolean existsBySlug(String slug);
}
