package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.licensing.*;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Platform Operations licensing API. Signed tokens are accepted but never returned. */
@RestController
@RequestMapping("/api/v1/platform/licensing")
public class LicensingController {
    private final TenantLicensingService licensingService;
    private final RequestContextResolver contextResolver;

    public LicensingController(TenantLicensingService licensingService, RequestContextResolver contextResolver) {
        this.licensingService = licensingService;
        this.contextResolver = contextResolver;
    }

    @GetMapping
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<OverviewResponse> overview() {
        return ResponseEntity.ok(OverviewResponse.from(licensingService.overview()));
    }

    @GetMapping("/tenants/{tenantId}")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<TenantLicenseResponse> status(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(TenantLicenseResponse.from(licensingService.status(TenantId.of(tenantId))));
    }

    @PostMapping("/tenants/{tenantId}/licenses")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<TenantLicenseResponse> install(@PathVariable UUID tenantId,
                                                          @RequestBody InstallLicenseRequest body,
                                                          HttpServletRequest request) {
        if (body == null || body.licenseToken() == null || body.licenseToken().isBlank()) {
            throw new ValidationException("Signed license token is required");
        }
        var principal = contextResolver.resolvePrincipal(request);
        var status = licensingService.install(TenantId.of(tenantId), body.licenseToken(), principal.actorId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(TenantLicenseResponse.from(status));
    }

    @GetMapping("/tenants/{tenantId}/history")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<List<HistoryResponse>> history(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(licensingService.history(TenantId.of(tenantId)).stream()
                .map(HistoryResponse::from).toList());
    }

    public record InstallLicenseRequest(String licenseToken) {}

    public record OverviewResponse(boolean enforcementEnabled, boolean verificationKeyConfigured,
                                   List<TenantLicenseResponse> tenants) {
        static OverviewResponse from(LicensingOverview overview) {
            return new OverviewResponse(overview.enforcementEnabled(), overview.verificationKeyConfigured(),
                    overview.tenants().stream().map(TenantLicenseResponse::from).toList());
        }
    }

    public record TenantLicenseResponse(UUID tenantId, String tenantName, String tenantSlug,
                                        String status, boolean processingAllowed, UUID licenseRecordId,
                                        ClaimsResponse claims, String reasonCode) {
        static TenantLicenseResponse from(TenantLicenseStatus status) {
            return new TenantLicenseResponse(status.tenantId().value(), status.tenantName(), status.tenantSlug(),
                    status.status().name(), status.processingAllowed(), status.licenseRecordId(),
                    ClaimsResponse.from(status.claims()), status.reasonCode());
        }
    }

    public record HistoryResponse(UUID recordId, UUID licenseId, Instant installedAt, UUID installedBy,
                                  boolean active, String status, ClaimsResponse claims, String reasonCode,
                                  String verificationEvidence) {
        static HistoryResponse from(TenantLicenseHistoryItem item) {
            return new HistoryResponse(item.recordId(), item.licenseId(), item.installedAt(), item.installedBy(),
                    item.active(), item.status(), ClaimsResponse.from(item.claims()), item.reasonCode(),
                    item.verificationEvidence());
        }
    }

    public record ClaimsResponse(int schemaVersion, UUID licenseId, UUID tenantId, String customerName,
                                 String edition, Instant issuedAt, Instant notBefore, Instant expiresAt,
                                 Set<String> entitlements, Map<String, Long> limits) {
        static ClaimsResponse from(LicenseClaims claims) {
            if (claims == null) return null;
            return new ClaimsResponse(claims.schemaVersion(), claims.licenseId(), claims.tenantId().value(),
                    claims.customerName(), claims.edition(), claims.issuedAt(), claims.notBefore(), claims.expiresAt(),
                    claims.entitlements(), claims.limits());
        }
    }
}
