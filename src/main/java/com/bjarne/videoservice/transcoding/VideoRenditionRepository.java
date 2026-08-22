package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.catalog.VideoRendition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoRenditionRepository extends JpaRepository<VideoRendition, Long> {
}
