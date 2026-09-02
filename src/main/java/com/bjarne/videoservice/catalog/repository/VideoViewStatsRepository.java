package com.bjarne.videoservice.catalog.repository;

import com.bjarne.videoservice.catalog.entity.VideoViewStats;
import com.bjarne.videoservice.catalog.entity.VideoViewStatsId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

public interface VideoViewStatsRepository extends JpaRepository<VideoViewStats, VideoViewStatsId> {

    /*
     * Atomic upsert instead of read-then-write (JpaRepository#save would hit a PK conflict on
     * two concurrent first views of the same day) - see ViewCountService.
     */
    @Modifying
    @Query(value = """
            INSERT INTO video_view_stats (video_id, day, views)
            VALUES (:videoId, :day, 1)
            ON CONFLICT (video_id, day) DO UPDATE SET views = video_view_stats.views + 1
            """, nativeQuery = true)
    void incrementViews(@Param("videoId") UUID videoId, @Param("day") LocalDate day);
}
