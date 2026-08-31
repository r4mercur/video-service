package com.bjarne.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.transcode")
public record TranscodeProperties(
        @DefaultValue("ffmpeg") String ffmpegPath,
        @DefaultValue("ffprobe") String ffprobePath,
        String workDir,
        @DefaultValue("PT2H") Duration jobTimeout,
        @DefaultValue("PT5S") Duration pollInterval,
        @DefaultValue("PT2H") Duration staleJobTimeout,
        @DefaultValue({"PT1M", "PT5M", "PT15M"}) List<Duration> retryBackoff,
        @DefaultValue({"360", "720", "1080"}) List<Integer> ladderHeights,
        // CLAUDE.md 9.2: covers the window where transcode defects (broken ladders, wrong
        // colour space, audio drift) typically surface, so a re-encode from the original is
        // still possible if something's wrong. See SourceRetentionCleanupJob.
        @DefaultValue("P30D") Duration sourceRetention,
        @DefaultValue("10") int spriteIntervalSeconds,
        @DefaultValue("10") int spriteColumns,
        @DefaultValue("160") int spriteTileWidth,
        @DefaultValue("90") int spriteTileHeight) {

    public Path workDirPath() {
        return workDir != null ? Path.of(workDir) : Path.of(System.getProperty("java.io.tmpdir"), "video-service-transcode");
    }
}
