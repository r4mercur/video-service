package com.bjarne.videoservice.transcoding.repository;

import com.bjarne.videoservice.catalog.entity.VideoRendition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoRenditionRepository extends JpaRepository<VideoRendition, Long> {

    List<VideoRendition> findByVideoId(UUID videoId);

    void deleteByVideoId(UUID videoId);
}
