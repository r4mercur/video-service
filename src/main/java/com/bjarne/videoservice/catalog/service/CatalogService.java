package com.bjarne.videoservice.catalog.service;

import com.bjarne.videoservice.catalog.dto.CategoryDto;
import com.bjarne.videoservice.catalog.dto.VideoDetailDto;
import com.bjarne.videoservice.catalog.dto.VideoSummaryDto;
import com.bjarne.videoservice.catalog.entity.Video;
import com.bjarne.videoservice.catalog.repository.CategoryRepository;
import com.bjarne.videoservice.catalog.repository.VideoRepository;
import com.bjarne.videoservice.delivery.service.MediaUrlResolver;
import com.bjarne.videoservice.identity.entity.User;
import com.bjarne.videoservice.identity.repository.UserRepository;
import com.bjarne.videoservice.shared.CursorCodec;
import com.bjarne.videoservice.shared.CursorPage;
import com.bjarne.videoservice.shared.PageResponse;
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

    private static final int SEARCH_PAGE_SIZE = 50;
    private static final int SEARCH_QUERY_MIN_LENGTH = 2;
    private static final int SEARCH_QUERY_MAX_LENGTH = 100;

    /**
     * Sentinel for "no cursor" (first page): guaranteed to sort after every real
     * published_at/createdAt, so the keyset query doesn't need a separate "cursorTs IS NULL"
     * branch - otherwise Postgres can't resolve the parameter type of a bare IS-NULL check
     * (SQLState 42P18).
     */
    private static final Instant NO_CURSOR_TS = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID NO_CURSOR_ID = new UUID(-1L, -1L);

    private final VideoRepository videoRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final MediaUrlResolver urlResolver;

    public CatalogService(VideoRepository videoRepository,
                          CategoryRepository categoryRepository,
                          UserRepository userRepository,
                          MediaUrlResolver urlResolver) {
        this.videoRepository = videoRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.urlResolver = urlResolver;
    }

    public List<CategoryDto> categories() {
        return categoryRepository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(CategoryDto::from)
                .toList();
    }

    public CursorPage<VideoSummaryDto> feed(String categorySlug, String sort, String cursor, Integer limit,
                                             boolean includeAgeRestricted) {
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
                includeAgeRestricted, PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, video -> VideoSummaryDto.from(video, urlResolver), Video::getPublishedAt);
    }

    public VideoDetailDto detail(String slug, UUID viewerUserId) {
        Video video = videoRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException("Video not found"));
        if (!VisibilityPolicy.isVisibleTo(video, viewerUserId)) {
            throw new NotFoundException("Video not found");
        }
        return VideoDetailDto.from(video, urlResolver);
    }

    public CursorPage<VideoDetailDto> myVideos(UUID userId, String cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<Video> videos = videoRepository.findByUser(userId, decoded.timestamp(), decoded.id(),
                PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, video -> VideoDetailDto.from(video, urlResolver), Video::getCreatedAt);
    }

    public CursorPage<VideoSummaryDto> channel(String username, String cursor, Integer limit,
                                                boolean includeAgeRestricted) {
        User owner = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
        int pageSize = resolveLimit(limit);
        CursorCodec.Cursor decoded = decodeCursor(cursor);
        List<Video> videos = videoRepository.findPublicByUser(owner.getId(), decoded.timestamp(), decoded.id(),
                includeAgeRestricted, PageRequest.of(0, pageSize + 1));
        return buildPage(videos, pageSize, video -> VideoSummaryDto.from(video, urlResolver), Video::getPublishedAt);
    }

    /**
     * Title search with numbered pages. OFFSET paging is a documented exception to CLAUDE.md 3.2:
     * results are relevance-ranked, so there is no stable keyset, and nobody pages deep into them.
     */
    public PageResponse<VideoSummaryDto> search(String query, String sort, Integer page, boolean includeAgeRestricted) {
        String normalizedQuery = query != null ? query.strip() : "";
        if (normalizedQuery.length() < SEARCH_QUERY_MIN_LENGTH || normalizedQuery.length() > SEARCH_QUERY_MAX_LENGTH) {
            throw new ValidationException("q must be between " + SEARCH_QUERY_MIN_LENGTH + " and "
                    + SEARCH_QUERY_MAX_LENGTH + " characters");
        }
        boolean sortByRelevance = resolveSearchSort(sort);
        int pageNumber = page != null ? page : 1;
        if (pageNumber < 1) {
            throw new ValidationException("page must be at least 1");
        }

        String pattern = "%" + escapeLikePattern(normalizedQuery) + "%";
        long totalItems = videoRepository.countPublicSearch(normalizedQuery, pattern, includeAgeRestricted);
        int totalPages = (int) ((totalItems + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE);
        long offset = (long) (pageNumber - 1) * SEARCH_PAGE_SIZE;
        if (offset >= totalItems) {
            return new PageResponse<>(List.of(), pageNumber, SEARCH_PAGE_SIZE, totalItems, totalPages);
        }

        List<VideoSummaryDto> items = videoRepository.searchPublic(normalizedQuery, pattern, includeAgeRestricted,
                        sortByRelevance, SEARCH_PAGE_SIZE, offset).stream()
                .map(video -> VideoSummaryDto.from(video, urlResolver))
                .toList();
        return new PageResponse<>(items, pageNumber, SEARCH_PAGE_SIZE, totalItems, totalPages);
    }

    private boolean resolveSearchSort(String sort) {
        if (sort == null || sort.equals("relevance")) {
            return true;
        }
        if (sort.equals("newest")) {
            return false;
        }
        throw new ValidationException("Unsupported sort value: " + sort);
    }

    /** Makes %, _ and the escape character itself match literally in an ILIKE pattern. */
    private static String escapeLikePattern(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
