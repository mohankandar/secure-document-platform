package com.securedoc.platform.application.licensing;

import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.*;
import com.securedoc.platform.domain.tenant.Tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant licensing foundation. License validation is tenant-scoped and independent of machine identity. */
public final class TenantLicensingService {
    private final TenantLicenseRepository licenseRepository;
    private final TenantRepository tenantRepository;
    private final LicenseTokenVerifier verifier;
    private final AuditService auditService;
    private final boolean enforcementEnabled;
    private final Clock clock;

    public TenantLicensingService(TenantLicenseRepository licenseRepository,
                                  TenantRepository tenantRepository,
                                  LicenseTokenVerifier verifier,
                                  AuditService auditService,
                                  boolean enforcementEnabled) {
        this(licenseRepository, tenantRepository, verifier, auditService, enforcementEnabled, Clock.systemUTC());
    }

    TenantLicensingService(TenantLicenseRepository licenseRepository,
                           TenantRepository tenantRepository,
                           LicenseTokenVerifier verifier,
                           AuditService auditService,
                           boolean enforcementEnabled,
                           Clock clock) {
        this.licenseRepository = Objects.requireNonNull(licenseRepository);
        this.tenantRepository = Objects.requireNonNull(tenantRepository);
        this.verifier = Objects.requireNonNull(verifier);
        this.auditService = Objects.requireNonNull(auditService);
        this.enforcementEnabled = enforcementEnabled;
        this.clock = Objects.requireNonNull(clock);
    }

    public LicensingOverview overview() {
        List<TenantLicenseStatus> statuses = tenantRepository.findAll().stream()
                .map(tenant -> statusFor(tenant, Instant.now(clock)))
                .toList();
        return new LicensingOverview(enforcementEnabled, verifier.isVerificationKeyConfigured(), statuses);
    }

    public TenantLicenseStatus status(TenantId tenantId) {
        Tenant tenant = requireTenant(tenantId);
        return statusFor(tenant, Instant.now(clock));
    }

    public TenantLicenseStatus install(TenantId tenantId, String signedToken, ActorId actorId,
                                       CorrelationId correlationId) {
        Tenant tenant = requireTenant(tenantId);
        if (signedToken == null || signedToken.isBlank()) {
            throw new IllegalArgumentException("Signed license token is required");
        }
        Instant now = Instant.now(clock);
        LicenseVerificationResult verification = verifier.verify(signedToken.trim(), tenantId, now);
        if (verification.status() != LicenseValidationStatus.ACTIVE
                && verification.status() != LicenseValidationStatus.NOT_YET_VALID) {
            auditService.recordFailure(tenantId, actorId, AuditAction.LICENSE_VALIDATION_FAILED,
                    "TenantLicense", tenantId.toString(), correlationId,
                    "License install rejected: status=" + verification.status());
            throw new LicenseEnforcementException(errorCode(verification.status()),
                    installFailureMessage(verification.status()), verification.status());
        }
        LicenseClaims claims = Objects.requireNonNull(verification.claims());
        TenantLicenseRecord record = new TenantLicenseRecord(
                UUID.randomUUID(), tenantId, claims.licenseId(), signedToken.trim(),
                sha256(signedPayloadPart(signedToken.trim())), actorId.value(), now);
        record = licenseRepository.save(record);
        licenseRepository.activate(tenantId, record.id(), actorId.value());
        auditService.recordSuccess(tenantId, actorId, AuditAction.LICENSE_INSTALLED,
                "TenantLicense", claims.licenseId().toString(), correlationId,
                "Tenant license installed: edition=" + claims.edition() + ", status=" + verification.status());
        return statusFor(tenant, now);
    }

    public List<TenantLicenseHistoryItem> history(TenantId tenantId) {
        requireTenant(tenantId);
        Instant now = Instant.now(clock);
        UUID activeRecordId = licenseRepository.findActive(tenantId).map(TenantLicenseRecord::id).orElse(null);
        return licenseRepository.findHistory(tenantId).stream()
                .map(record -> historyItem(record, activeRecordId, tenantId, now))
                .toList();
    }

    private TenantLicenseHistoryItem historyItem(TenantLicenseRecord record, UUID activeRecordId,
                                                 TenantId tenantId, Instant now) {
        boolean active = record.id().equals(activeRecordId);
        LicenseVerificationResult currentVerification = verifier.verify(record.signedToken(), tenantId, now);
        LicenseClaims claims = currentVerification.claims();
        String evidence = "CURRENT_KEY";

        if (claims == null) {
            claims = verifier.readPersistedVerifiedClaims(record).orElse(null);
            evidence = claims == null ? "UNAVAILABLE" : "VERIFIED_AT_INSTALL";
        }

        if (active) {
            return new TenantLicenseHistoryItem(record.id(), record.licenseId(), record.installedAt(), record.installedBy(),
                    true, currentVerification.status().name(), claims, currentVerification.reasonCode(), evidence);
        }

        String historicalStatus = currentVerification.status().name();
        String reasonCode = currentVerification.reasonCode();
        if (claims != null) {
            if (!now.isBefore(claims.expiresAt())) {
                historicalStatus = LicenseValidationStatus.EXPIRED.name();
                reasonCode = "LICENSE_EXPIRED";
            } else if (claims.notBefore() != null && now.isBefore(claims.notBefore())) {
                historicalStatus = LicenseValidationStatus.NOT_YET_VALID.name();
                reasonCode = "LICENSE_NOT_YET_VALID";
            } else {
                historicalStatus = "SUPERSEDED";
                reasonCode = "LICENSE_SUPERSEDED";
            }
        }

        return new TenantLicenseHistoryItem(record.id(), record.licenseId(), record.installedAt(), record.installedBy(),
                false, historicalStatus, claims, reasonCode, evidence);
    }

    public void requireDocumentProcessing(TenantId tenantId, ActorId actorId, CorrelationId correlationId) {
        if (!enforcementEnabled) return;
        TenantLicenseStatus state = status(tenantId);
        if (state.processingAllowed()) return;
        auditService.recordDenied(tenantId, actorId, AuditAction.LICENSE_ENFORCEMENT_DENIED,
                "Tenant", tenantId.toString(), correlationId,
                "Document processing denied by tenant license: status=" + state.status()
                        + ", reason=" + state.reasonCode());
        throw new LicenseEnforcementException(errorCode(state.status()), enforcementMessage(state), state.status());
    }

    public boolean enforcementEnabled() {
        return enforcementEnabled;
    }

    private TenantLicenseStatus statusFor(Tenant tenant, Instant now) {
        if (!enforcementEnabled) {
            return new TenantLicenseStatus(tenant.id(), tenant.name(), tenant.slug(),
                    LicenseValidationStatus.NOT_ENFORCED, true, null, null, "LICENSING_NOT_ENFORCED");
        }
        var active = licenseRepository.findActive(tenant.id());
        if (active.isEmpty()) {
            return new TenantLicenseStatus(tenant.id(), tenant.name(), tenant.slug(),
                    LicenseValidationStatus.MISSING, false, null, null, "LICENSE_MISSING");
        }
        TenantLicenseRecord record = active.get();
        LicenseVerificationResult verification = verifier.verify(record.signedToken(), tenant.id(), now);
        boolean allowed = verification.status() == LicenseValidationStatus.ACTIVE
                && verification.claims() != null
                && verification.claims().hasEntitlement(LicenseEntitlement.DOCUMENT_PROCESSING);
        String reason = verification.reasonCode();
        if (verification.status() == LicenseValidationStatus.ACTIVE && !allowed) {
            reason = "DOCUMENT_PROCESSING_ENTITLEMENT_MISSING";
        }
        return new TenantLicenseStatus(tenant.id(), tenant.name(), tenant.slug(), verification.status(),
                allowed, record.id(), verification.claims(), reason);
    }

    private Tenant requireTenant(TenantId tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant"));
    }

    private static String signedPayloadPart(String token) {
        String[] parts = token.split("\\.", -1);
        return parts.length == 3 ? parts[1] : token;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String errorCode(LicenseValidationStatus status) {
        return switch (status) {
            case MISSING -> "LICENSE_REQUIRED";
            case EXPIRED -> "LICENSE_EXPIRED";
            case NOT_YET_VALID -> "LICENSE_NOT_YET_VALID";
            case TENANT_MISMATCH -> "LICENSE_TENANT_MISMATCH";
            case INVALID_SIGNATURE -> "LICENSE_INVALID_SIGNATURE";
            case UNSUPPORTED_SCHEMA -> "LICENSE_UNSUPPORTED_SCHEMA";
            case MALFORMED -> "LICENSE_MALFORMED";
            case CONFIGURATION_ERROR -> "LICENSE_CONFIGURATION_ERROR";
            default -> "LICENSE_NOT_PERMITTED";
        };
    }

    private static String installFailureMessage(LicenseValidationStatus status) {
        return switch (status) {
            case CONFIGURATION_ERROR -> "LexaVault license verification key is not configured";
            case INVALID_SIGNATURE -> "License signature is invalid";
            case TENANT_MISMATCH -> "License is registered to a different tenant";
            case EXPIRED -> "License is expired";
            case MALFORMED -> "License token is malformed";
            case UNSUPPORTED_SCHEMA -> "License schema version is not supported";
            default -> "License cannot be installed in its current state";
        };
    }

    private static String enforcementMessage(TenantLicenseStatus state) {
        if ("DOCUMENT_PROCESSING_ENTITLEMENT_MISSING".equals(state.reasonCode())) {
            return "Tenant license does not include DOCUMENT_PROCESSING";
        }
        return switch (state.status()) {
            case MISSING -> "A valid tenant license is required before document intake or processing can continue";
            case EXPIRED -> "Tenant license has expired";
            case NOT_YET_VALID -> "Tenant license is not yet valid";
            case TENANT_MISMATCH -> "Installed license does not match this tenant";
            case INVALID_SIGNATURE -> "Installed tenant license signature is invalid";
            case CONFIGURATION_ERROR -> "LexaVault license verification is not configured";
            default -> "Tenant license does not permit document processing";
        };
    }
}
