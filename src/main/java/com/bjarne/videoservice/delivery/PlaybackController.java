package com.bjarne.videoservice.delivery;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Oeffentlich (mit optionalem JWT) - Sichtbarkeits-/Ownership-Pruefung passiert vollstaendig in
 * {@link PlaylistService}, siehe SecurityConfig fuer die permitAll-Matcher dieser Pfade.
 */
@RestController
public class PlaybackController {

    private static final MediaType HLS_PLAYLIST = MediaType.valueOf("application/vnd.apple.mpegurl");

    private final PlaylistService playlistService;

    public PlaybackController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping("/api/videos/{id}/manifest")
    public ManifestResponse manifest(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return playlistService.manifest(id, viewerUserId(jwt));
    }

    @GetMapping("/api/videos/{id}/master.m3u8")
    public ResponseEntity<String> masterPlaylist(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String playlist = playlistService.masterPlaylist(id, viewerUserId(jwt));
        return playlistResponse(playlist);
    }

    @GetMapping("/api/videos/{id}/{height:[0-9]+}p/playlist.m3u8")
    public ResponseEntity<String> renditionPlaylist(@PathVariable UUID id, @PathVariable int height,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String playlist = playlistService.renditionPlaylist(id, height, viewerUserId(jwt));
        return playlistResponse(playlist);
    }

    private ResponseEntity<String> playlistResponse(String playlist) {
        return ResponseEntity.ok()
                .contentType(HLS_PLAYLIST)
                .cacheControl(CacheControl.noStore())
                .body(playlist);
    }

    private UUID viewerUserId(Jwt jwt) {
        return jwt != null ? UUID.fromString(jwt.getSubject()) : null;
    }
}
