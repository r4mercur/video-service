package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.shared.CursorCodec;
import com.bjarne.videoservice.shared.CursorPage;
import com.bjarne.videoservice.shared.exceptions.NotFoundException;
import com.bjarne.videoservice.shared.exceptions.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /**
     * Sentinel fuer "kein Cursor" (erste Seite): garantiert nach jedem echten published_at/createdAt
     * liegend, damit die Keyset-Query ohne separate "cursorTs IS NULL"-Verzweigung auskommt - Postgres
     * kann sonst den Parametertyp einer bare IS-NULL-Pruefung nicht auflösen (SQLState 42P18).
     */
    private static final Instant NO_CURSOR_TS = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID NO_CURSOR_ID = new UUID(-1L, -1L);

    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public CatalogService(VideoRepository videoRepository, CategoryRepository categoryRepository,
                           UserRepository userRepository) {
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    public List<CategoryDto> categories() {
        return categoryRepository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(CategoryDto::from)
                .toList();
    }

    public CursorPage<VideoSummaryDto> feed(String categorySlug, String sort, String cursor, Integer limit) {
        if (sort != null && !sort.equals("newest")) {
            throw new ValidationException("Unsupported sort value: " + sort);
        }
        Long categoryId = null;
        if (categorySlug != null) {
            categoryId = categoryRepository.findBySlug(categorySlug)
                    .orElseThrow(() -> new NotFoundException("Category not found"))
                    .getId();
        }
        int pageSize = resolveLimit(limit);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<Video> videos = videoRepository.findPublicFeed(categoryId, decoded.timestamp(), decoded.id(),
                PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, VideoSummaryDto::from, Video::getPublishedAt);
    }

    public VideoDetailDto detail(String slug, UUID viewerUserId) {
        Video video = videoRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException("Video not found"));
        if (!VisibilityPolicy.isVisibleTo(video, viewerUserId)) {
            throw new NotFoundException("Video not found");
        }
        return VideoDetailDto.from(video);
    }

    public CursorPage<VideoDetailDto> myVideos(UUID userId, String cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<Video> videos = videoRepository.findByUser(userId, decoded.timestamp(), decoded.id(),
                PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, VideoDetailDto::from, Video::getCreatedAt);
    }

    public CursorPage<VideoSummaryDto> channel(String username, String cursor, Integer limit) {
        User owner = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
        int pageSize = resolveLimit(limit);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<Video> videos = videoRepository.findPublicByUser(owner.getId(), decoded.timestamp(), decoded.id(),
                PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, VideoSummaryDto::from, Video::getPublishedAt);
    }

    private CursorCodec.Cursor decodeCursor(String cursor) {
        return cursor != null ? CursorCodec.decode(cursor) : new CursorCodec.Cursor(NO_CURSOR_TS, NO_CURSOR_ID);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private <T> CursorPage<T> buildPage(List<Video> videos, int pageSize, Function<Video, T> mapper,
                                         Function<Video, Instant> cursorTimestamp) {
        boolean hasNext = videos.size() > pageSize;
        List<Video> pageItems = hasNext ? videos.subList(0, pageSize) : videos;
        String nextCursor = null;
        if (hasNext) {
            Video last = pageItems.get(pageItems.size() - 1);
            nextCursor = CursorCodec.encode(cursorTimestamp.apply(last), last.getId());
        }
        return new CursorPage<>(pageItems.stream().map(mapper).toList(), nextCursor);
    }
}
