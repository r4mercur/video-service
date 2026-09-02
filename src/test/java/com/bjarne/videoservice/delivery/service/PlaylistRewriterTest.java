package com.bjarne.videoservice.delivery.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistRewriterTest {

    private final PlaylistRewriter rewriter = new PlaylistRewriter();

    @Test
    void rewritesInitMapAndSegmentLinesToResolvedUrls() {
        String raw = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:4
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.000,
                segment_000.m4s
                #EXTINF:4.000,
                segment_001.m4s
                #EXT-X-ENDLIST
                """;

        String rewritten = rewriter.rewriteRenditionPlaylist(raw, filename -> "https://storage.example/" + filename + "?sig=abc");

        assertThat(rewritten).contains("#EXT-X-MAP:URI=\"https://storage.example/init.mp4?sig=abc\"");
        assertThat(rewritten).contains("https://storage.example/segment_000.m4s?sig=abc");
        assertThat(rewritten).contains("https://storage.example/segment_001.m4s?sig=abc");
        assertThat(rewritten).doesNotContain("\nsegment_000.m4s\n");
    }

    @Test
    void leavesCommentAndTagLinesUntouched() {
        String raw = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:4
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-ENDLIST
                """;

        String rewritten = rewriter.rewriteRenditionPlaylist(raw, filename -> {
            throw new AssertionError("resolver should not be called when there are no segment lines: " + filename);
        });

        assertThat(rewritten).isEqualTo(raw);
    }

    @Test
    void preservesLineCountAndOrdering() {
        String raw = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nsegment_000.m4s\n#EXT-X-ENDLIST";

        String rewritten = rewriter.rewriteRenditionPlaylist(raw, filename -> "SIGNED(" + filename + ")");

        assertThat(rewritten).isEqualTo(
                "#EXTM3U\n#EXT-X-MAP:URI=\"SIGNED(init.mp4)\"\nSIGNED(segment_000.m4s)\n#EXT-X-ENDLIST");
    }
}
