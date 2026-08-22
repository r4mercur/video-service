package com.bjarne.videoservice.delivery;

import com.bjarne.videoservice.catalog.Video;
import com.bjarne.videoservice.catalog.VideoRepository;
import com.bjarne.videoservice.catalog.Visibility;
import com.bjarne.videoservice.catalog.VisibilityPolicy;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.UUID;

/**
 * Ausspielung von Manifest/Playlists (AP6, CLAUDE.md 9.3). Sichtbarkeits-/Ownership-Pruefung
 * laeuft ausschliesslich ueber {@link VisibilityPolicy} (CLAUDE.md 3.2) - hier nicht dupliziert.
 * Bei fremdem PRIVATE-Video oder noch nicht verarbeitetem Video gibt es bewusst ueberall 404
 * statt 403 (CLAUDE.md Abschnitt 7: 403 wuerde die Existenz des Videos verraten).
 */
@Service
@Transactional(readOnly = true)
public class PlaylistService {

    private final VideoRepository videoRepository;
    private final PlaylistObjectStore objectStore;
    private final PlaylistRewriter rewriter;
    private final ObjectPresigner presigner;

    public PlaylistService(VideoRepository videoRepository, PlaylistObjectStore objectStore,
                            PlaylistRewriter rewriter, ObjectPresigner presigner) {
        this.videoRepository = videoRepository;
        this.objectStore = objectStore;
        this.rewriter = rewriter;
        this.presigner = presigner;
    }

    public ManifestResponse manifest(UUID videoId, UUID viewerUserId) {
        Video video = requireReadyAndAccessible(videoId, viewerUserId);
        String playlistUrl = video.getVisibility() == Visibility.PUBLIC
                ? "/" + video.getStoragePrefix() + "/master.m3u8"
                : "/api/videos/" + video.getId() + "/master.m3u8";
        return new ManifestResponse(playlistUrl);
    }

    public String masterPlaylist(UUID videoId, UUID viewerUserId) {
        Video video = requireReadyAndAccessible(videoId, viewerUserId);
        return fetch(video.getPlaylistKey());
    }

    public String renditionPlaylist(UUID videoId, int height, UUID viewerUserId) {
        Video video = requireReadyAndAccessible(videoId, viewerUserId);
        String renditionPrefix = video.getStoragePrefix() + "/" + height + "p";
        String raw = fetch(renditionPrefix + "/playlist.m3u8");
        return rewriter.rewriteRenditionPlaylist(raw,
                filename -> presigner.presignGet(renditionPrefix + "/" + filename));
    }

    private Video requireReadyAndAccessible(UUID videoId, UUID viewerUserId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new NotFoundException("Video not found: " + videoId));
        if (video.getPlaylistKey() == null || !VisibilityPolicy.isVisibleTo(video, viewerUserId)) {
            throw new NotFoundException("Video not found: " + videoId);
        }
        return video;
    }

    private String fetch(String key) {
        try {
            return objectStore.fetch(key);
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Playlist object not found: " + key);
        }
    }
}
