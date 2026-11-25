package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.review.result.ResolveReviewItemResult;
import com.securedoc.platform.application.review.service.FocusedReviewService;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.FindingId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Task 0074: "I have reviewed this page", with the boxes drawn on it. */
@RestController
@RequestMapping("/api/v1/review-items")
public class ReviewPageCheckController {
    private final FocusedReviewService service;
    private final RequestContextResolver contextResolver;
    private final TransactionTemplate tx;

    public ReviewPageCheckController(FocusedReviewService service, RequestContextResolver contextResolver,
                                     PlatformTransactionManager transactions) {
        this.service = service;
        this.contextResolver = contextResolver;
        this.tx = new TransactionTemplate(transactions);
    }

    @PostMapping("/{findingId}/page-checked")
    @RequireCapability(UserCapability.FINDING_DECISION)
    public ResponseEntity<Map<String, Object>> pageChecked(@PathVariable UUID findingId,
                                                           @RequestBody(required = false) PageCheckRequest body,
                                                           HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var correlationId = contextResolver.resolveCorrelationId(request);
        List<FindingLocation.BoundingBox> boxes = boxes(body);
        ResolveReviewItemResult result;
        try {
            result = tx.execute(status -> service.checkPage(FindingId.of(findingId), principal.tenantId(),
                    principal.actorId(), correlationId, body != null && Boolean.TRUE.equals(body.reviewed()), boxes));
        } catch (OptimisticLockingFailureException someoneElse) {
            // Two people at once: one check stands, with its boxes; this one left nothing.
            throw new ReviewAlreadyDecidedException();
        }
        return ResponseEntity.ok(Map.of(
                "findingId", result.findingId().toString(),
                "packageId", result.packageId().toString(),
                "status", result.status().name(),
                "processingResumed", result.processingResumed()));
    }

    private static List<FindingLocation.BoundingBox> boxes(PageCheckRequest body) {
        if (body == null || body.boxes() == null) return List.of();
        try {
            return body.boxes().stream().map(b -> {
                if (!(b.width() > 0) || !(b.height() > 0)) throw new IllegalArgumentException("empty");
                return FindingLocation.BoundingBox.of(b.left(), b.top(), b.width(), b.height());
            }).toList();
        } catch (IllegalArgumentException | NullPointerException outside) {
            throw new ValidationException(LvErrorCode.REV_006, "A box is outside the page");
        }
    }

    public record PageCheckRequest(Boolean reviewed, List<Box> boxes) {
    }

    public record Box(double left, double top, double width, double height) {
    }
}
