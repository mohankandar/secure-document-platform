package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireRole;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.GetReviewPagePreviewQuery;
import com.securedoc.platform.application.port.in.ListReviewItemsQuery;
import com.securedoc.platform.application.port.in.ResolveReviewItemUseCase;
import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.application.review.result.ResolveReviewItemResult;
import com.securedoc.platform.application.review.result.ReviewItemSummary;
import com.securedoc.platform.application.review.result.ReviewPagePreviewResult;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V1 role-based focused review API. It intentionally exposes no assignment/create/start-task
 * mechanics. The preview endpoint returns only the relevant rendered source page, not the
 * original document binary.
 */
@RestController
@RequestMapping("/api/v1/review-items")
public class FocusedReviewController {

    private final ListReviewItemsQuery listReviewItemsQuery;
    private final ResolveReviewItemUseCase resolveReviewItemUseCase;
    private final GetReviewPagePreviewQuery getReviewPagePreviewQuery;
    private final RequestContextResolver contextResolver;

    public FocusedReviewController(ListReviewItemsQuery listReviewItemsQuery,
                                   ResolveReviewItemUseCase resolveReviewItemUseCase,
                                   GetReviewPagePreviewQuery getReviewPagePreviewQuery,
                                   RequestContextResolver contextResolver) {
        this.listReviewItemsQuery = listReviewItemsQuery;
        this.resolveReviewItemUseCase = resolveReviewItemUseCase;
        this.getReviewPagePreviewQuery = getReviewPagePreviewQuery;
        this.contextResolver = contextResolver;
    }

    @GetMapping
    @RequireRole({"REVIEWER"})
    public ResponseEntity<List<ReviewItemSummary>> list(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(listReviewItemsQuery.execute(principal.tenantId(), limit));
    }

    @GetMapping(value = "/{findingId}/page-preview", produces = MediaType.IMAGE_PNG_VALUE)
    @RequireRole({"REVIEWER"})
    public ResponseEntity<byte[]> pagePreview(
            @PathVariable String findingId,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(request);
        ReviewPagePreviewResult preview = getReviewPagePreviewQuery.execute(
                principal.tenantId(), FindingId.of(UUID.fromString(findingId)),
                principal.actorId(), correlationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(preview.mimeType()))
                .header("X-Review-Page-Number", Integer.toString(preview.pageNumber()))
                .header("X-Review-Image-Width", Integer.toString(preview.pixelWidth()))
                .header("X-Review-Image-Height", Integer.toString(preview.pixelHeight()))
                .body(preview.content());
    }

    @PostMapping("/{findingId}/confirm")
    @RequireRole({"REVIEWER"})
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String findingId,
            @RequestBody(required = false) ConfirmRequest body,
            HttpServletRequest request) {
        ConfirmRequest safeBody = body == null ? new ConfirmRequest(null, null) : body;
        return resolve(findingId, ReviewResolution.CONFIRM, safeBody.reason(), safeBody.boundingBox(), request);
    }

    @PostMapping("/{findingId}/dismiss")
    @RequireRole({"REVIEWER"})
    public ResponseEntity<Map<String, Object>> dismiss(
            @PathVariable String findingId,
            @RequestBody(required = false) DismissRequest body,
            HttpServletRequest request) {
        String reason = body == null ? null : body.reason();
        return resolve(findingId, ReviewResolution.DISMISS, reason, null, request);
    }

    private ResponseEntity<Map<String, Object>> resolve(
            String findingId,
            ReviewResolution resolution,
            String reason,
            BoundingBoxRequest box,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(request);

        ResolveReviewItemCommand command = new ResolveReviewItemCommand(
                FindingId.of(UUID.fromString(findingId)), principal.tenantId(), principal.actorId(),
                resolution, reason,
                box == null ? null : box.left(),
                box == null ? null : box.top(),
                box == null ? null : box.width(),
                box == null ? null : box.height(),
                correlationId);
        ResolveReviewItemResult result = resolveReviewItemUseCase.execute(command);

        return ResponseEntity.ok(Map.of(
                "findingId", result.findingId().toString(),
                "packageId", result.packageId().toString(),
                "status", result.status().name(),
                "processingResumed", result.processingResumed()));
    }

    public record ConfirmRequest(String reason, BoundingBoxRequest boundingBox) {}
    public record DismissRequest(String reason) {}
    public record BoundingBoxRequest(double left, double top, double width, double height) {}
}
