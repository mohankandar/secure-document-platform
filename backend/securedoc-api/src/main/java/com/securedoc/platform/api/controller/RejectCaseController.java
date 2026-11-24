package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.review.service.RejectCaseService;
import com.securedoc.platform.application.review.service.RejectCaseService.Step;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.review.RejectionReason;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Task 0071: "Reject case" in Review (whoever may review) and in Approval (whoever may approve). */
@RestController
@RequestMapping("/api/v1/packages/{packageId}")
public class RejectCaseController {
    private final RejectCaseService service;
    private final RequestContextResolver contextResolver;

    public RejectCaseController(RejectCaseService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @PostMapping("/review/reject")
    @Transactional
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Map<String, String>> inReview(@PathVariable UUID packageId,
                                                        @RequestBody(required = false) RejectRequest body,
                                                        HttpServletRequest request) {
        return reject(packageId, body, request, Step.REVIEW);
    }

    @PostMapping("/approval/reject")
    @Transactional
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Map<String, String>> inApproval(@PathVariable UUID packageId,
                                                          @RequestBody(required = false) RejectRequest body,
                                                          HttpServletRequest request) {
        return reject(packageId, body, request, Step.APPROVAL);
    }

    private ResponseEntity<Map<String, String>> reject(UUID packageId, RejectRequest body, HttpServletRequest request,
                                                       Step step) {
        var principal = contextResolver.resolvePrincipal(request);
        RejectionReason reason = service.reject(PackageId.of(packageId), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), step, body == null ? null : body.reasonCode());
        return ResponseEntity.ok(Map.of("packageId", packageId.toString(), "status", "REJECTED",
                "reasonCode", reason.code().code()));
    }

    /** Only a code of the fixed list; anything else is refused and never repeated. */
    public record RejectRequest(String reasonCode) {
    }
}
