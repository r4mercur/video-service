package com.bjarne.videoservice.delivery;

import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reine Text-Transformation einer HLS-Rendition-Playlist (kein Storage-/DB-Zugriff, daher ohne
 * Spring-Kontext gut testbar). Die Master-Playlist selbst braucht KEIN Rewriting: wird sie unter
 * {@code /api/videos/{id}/master.m3u8} ausgeliefert, loesen HLS-Player die darin enthaltenen
 * relativen Rendition-Pfade (z.B. "360p/playlist.m3u8") automatisch gegen genau diese URL auf -
 * das trifft zufaellig exakt den Rendition-Endpunkt. Nur innerhalb einer Rendition-Playlist muss
 * jede Segment-/Init-Referenz durch eine absolute presignte Storage-URL ersetzt werden, sonst
 * wuerde der Player versuchen, Segmente vom Backend statt direkt vom Storage zu laden.
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
