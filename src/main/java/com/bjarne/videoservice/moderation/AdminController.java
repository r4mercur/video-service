package com.bjarne.videoservice.moderation;

import com.bjarne.videoservice.catalog.AdminCategoryDto;
import com.bjarne.videoservice.catalog.CategoryAdminService;
import com.bjarne.videoservice.catalog.CreateCategoryRequest;
import com.bjarne.videoservice.catalog.UpdateCategoryRequest;
import com.bjarne.videoservice.shared.CursorPage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final CategoryAdminService categoryAdminService;

    public AdminController(AdminService adminService, CategoryAdminService categoryAdminService) {
        this.adminService = adminService;
        this.categoryAdminService = categoryAdminService;
    }

    @GetMapping("/api/admin/categories")
    public List<AdminCategoryDto> listCategories() {
        return categoryAdminService.listAll();
    }

    @PostMapping("/api/admin/categories")
    public ResponseEntity<AdminCategoryDto> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryAdminService.create(request));
    }

    @PatchMapping("/api/admin/categories/{id}")
    public AdminCategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody UpdateCategoryRequest request) {
        return categoryAdminService.update(id, request);
    }

    @PostMapping("/api/admin/videos/{id}/block")
    public ResponseEntity<Void> blockVideo(@PathVariable UUID id, @Valid @RequestBody ModerationActionRequest request,
                                            JwtAuthenticationToken authentication) {
        adminService.blockVideo(id, adminUserId(authentication), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/videos/{id}/unblock")
    public ResponseEntity<Void> unblockVideo(@PathVariable UUID id, @Valid @RequestBody ModerationActionRequest request,
                                              JwtAuthenticationToken authentication) {
        adminService.unblockVideo(id, adminUserId(authentication), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/reports")
    public CursorPage<AdminReportDto> listReports(@RequestParam(required = false) ReportStatus status,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        return adminService.listReports(status, cursor, limit);
    }

    @PostMapping("/api/admin/reports/{id}/dismiss")
    public AdminReportDto dismissReport(@PathVariable Long id, @Valid @RequestBody ModerationActionRequest request,
                                         JwtAuthenticationToken authentication) {
        return adminService.dismissReport(id, adminUserId(authentication), request.reason());
    }

    @PostMapping("/api/admin/reports/{id}/uphold")
    public AdminReportDto upholdReport(@PathVariable Long id, @Valid @RequestBody ModerationActionRequest request,
                                        JwtAuthenticationToken authentication) {
        return adminService.upholdReport(id, adminUserId(authentication), request.reason());
    }

    private UUID adminUserId(JwtAuthenticationToken authentication) {
        return UUID.fromString(authentication.getToken().getSubject());
    }
}
