package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.shared.CursorPage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/api/categories")
    public List<CategoryDto> categories() {
        return catalogService.categories();
    }

    @GetMapping("/api/videos")
    public CursorPage<VideoSummaryDto> feed(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false, defaultValue = "false") boolean includeAgeRestricted) {
        return catalogService.feed(category, sort, cursor, limit, includeAgeRestricted);
    }

    @GetMapping("/api/videos/{slug}")
    public VideoDetailDto detail(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        return catalogService.detail(slug, viewerUserId(jwt));
    }

    @GetMapping("/api/me/videos")
    public CursorPage<VideoDetailDto> myVideos(@RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer limit,
                                                JwtAuthenticationToken authentication) {
        UUID userId = UUID.fromString(authentication.getToken().getSubject());
        return catalogService.myVideos(userId, cursor, limit);
    }

    @GetMapping("/api/users/{username}/videos")
    public CursorPage<VideoSummaryDto> channel(@PathVariable String username,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false, defaultValue = "false") boolean includeAgeRestricted) {
        return catalogService.channel(username, cursor, limit, includeAgeRestricted);
    }

    private UUID viewerUserId(Jwt jwt) {
        return jwt != null ? UUID.fromString(jwt.getSubject()) : null;
    }
}
