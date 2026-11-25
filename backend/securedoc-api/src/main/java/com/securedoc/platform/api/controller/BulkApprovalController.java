package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.service.BulkApprovalService;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Task 0076: bulk approval. Always the signed-in person's own entity; whoever may approve one case may use it. */
@RestController
@RequestMapping("/api/v1/bulk-approval")
public class BulkApprovalController {
    /** The Cases list is at most this long. */
    private static final int MAX_ASKED = 500;

    private final BulkApprovalService service;
    private final RequestContextResolver contextResolver;

    public BulkApprovalController(BulkApprovalService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    /** Which of these cases the person can't select, because they reviewed them. Changes nothing. */
    @PostMapping("/offer")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<OfferResponse> offer(@RequestBody(required = false) CasesRequest body, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var offer = service.offer(principal.tenantId(), principal.actorId(), ids(body, MAX_ASKED));
        return ResponseEntity.ok(new OfferResponse(offer.enabled(), BulkApprovalService.MAX_CASES,
                offer.reviewedByYou().stream().map(PackageId::toString).sorted().toList()));
    }

    @PostMapping
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<List<OutcomeResponse>> approve(@RequestBody(required = false) CasesRequest body,
                                                         HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(service.approve(principal.tenantId(), principal.actorId(),
                        contextResolver.resolveCorrelationId(request), ids(body, MAX_ASKED)).stream()
                .map(o -> new OutcomeResponse(o.packageId().toString(), o.approved(),
                        o.reason() == null ? null : o.reason().code()))
                .toList());
    }

    private static List<PackageId> ids(CasesRequest body, int max) {
        List<UUID> ids = body == null || body.packageIds() == null ? List.of() : body.packageIds();
        if (ids.size() > max || ids.contains(null)) {
            throw new ValidationException(LvErrorCode.INT_020, "The list of cases is not valid");
        }
        return ids.stream().map(PackageId::of).toList();
    }

    public record CasesRequest(List<UUID> packageIds) {
    }

    public record OfferResponse(boolean enabled, int maximum, List<String> reviewedByYou) {
    }

    public record OutcomeResponse(String packageId, boolean approved, String reasonCode) {
    }
}
