package com.bjarne.videoservice.transcoding;

import com.bjarne.videoservice.config.TranscodeProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reine Planungslogik (CLAUDE.md 9.2 CPU-Gegenmassnahmen): keine Renditions oberhalb der
 * Quellaufloesung, Stream-Copy statt Re-Encode wenn die Quelle in der Ziel-Rendition bereits
 * passend codiert ist.
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
            // Quelle ist kleiner als die kleinste Sprosse: kein Upscale, einzelne Rendition
            // in Quellaufloesung statt einer erfundenen 360p-Variante.
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
