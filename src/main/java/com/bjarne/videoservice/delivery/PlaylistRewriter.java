package com.bjarne.videoservice.delivery;

import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text transformation of an HLS rendition playlist (no storage/DB access, hence easily
 * testable without a Spring context). The master playlist itself needs NO rewriting: when
 * delivered under {@code /api/videos/{id}/master.m3u8}, HLS players automatically resolve the
 * relative rendition paths it contains (e.g. "360p/playlist.m3u8") against exactly this URL -
 * which happens to land exactly on the rendition endpoint. Only within a rendition playlist
 * does every segment/init reference need to be replaced with an absolute presigned storage URL,
 * otherwise the player would try to load segments from the backend instead of directly from
 * storage.
 */
@Component
public class PlaylistRewriter {

    private static final Pattern MAP_URI = Pattern.compile("URI=\"([^\"]*)\"");

    public String rewriteRenditionPlaylist(String rawPlaylist, Function<String, String> resolveRelativeUri) {
        String[] lines = rawPlaylist.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("#EXT-X-MAP:")) {
                result.append(rewriteMapLine(line, resolveRelativeUri));
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.append(resolveRelativeUri.apply(trimmed));
            } else {
                result.append(line);
            }
            if (i < lines.length - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private String rewriteMapLine(String line, Function<String, String> resolveRelativeUri) {
        Matcher matcher = MAP_URI.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        String resolved = resolveRelativeUri.apply(matcher.group(1));
        return line.substring(0, matcher.start(1)) + resolved + line.substring(matcher.end(1));
    }
}
