package com.bjarne.videoservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

public interface VideoViewStatsRepository extends JpaRepository<VideoViewStats, VideoViewStatsId> {

    /*
     * Atomarer Upsert statt read-then-write (JpaRepository#save wuerde bei zwei gleichzeitigen
     * ersten Views desselben Tages auf einen PK-Konflikt laufen) - siehe ViewCountService.
     */
    @Modifying
    @Query(value = """
            INSERT INTO video_view_stats (video_id, day, views)
            VALUES (:videoId, :day, 1)
            ON CONFLICT (video_id, day) DO UPDATE SET views = video_view_stats.views + 1
            """, nativeQuery = true)
    void incrementViews(@Param("videoId") UUID videoId, @Param("day") LocalDate day);
}
