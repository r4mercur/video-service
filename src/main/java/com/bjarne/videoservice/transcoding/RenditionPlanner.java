package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure planning logic (CLAUDE.md 9.2 CPU countermeasures): no renditions above the source
 * resolution, stream copy instead of re-encode when the source is already suitably encoded
 * for the target rendition.
 */
@Component
public class RenditionPlanner {

    private final TranscodeProperties properties;

    public RenditionPlanner(TranscodeProperties properties) {
        this.properties = properties;
    }

    public record PlannedRendition(int height, boolean streamCopy) {
    }

    public List<PlannedRendition> plan(MediaInfo sourceInfo) {
        List<Integer> heights = properties.ladderHeights().stream()
                .filter(height -> height <= sourceInfo.height())
                .sorted()
                .toList();

        if (heights.isEmpty()) {
            // Source is smaller than the smallest rung: no upscale, single rendition
            // at the source resolution instead of a made-up 360p variant.
            heights = List.of(sourceInfo.height());
        }

        int topHeight = heights.stream().max(Comparator.naturalOrder()).orElseThrow();
        List<PlannedRendition> plan = new ArrayList<>(heights.size());
        for (int height : heights) {
            boolean streamCopy = height == topHeight && height == sourceInfo.height()
                    && sourceInfo.isAlreadyH264HighAndAac();
            plan.add(new PlannedRendition(height, streamCopy));
        }
        return plan;
    }
}
