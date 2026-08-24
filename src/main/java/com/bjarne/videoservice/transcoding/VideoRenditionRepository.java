package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.VideoRendition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoRenditionRepository extends JpaRepository<VideoRendition, Long> {

    List<VideoRendition> findByVideoId(UUID videoId);

    void deleteByVideoId(UUID videoId);
}
