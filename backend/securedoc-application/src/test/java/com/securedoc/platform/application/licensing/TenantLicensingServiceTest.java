package com.securedoc.platform.application.licensing;

import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.*;
import com.securedoc.platform.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TenantLicensingServiceTest {

    @Test
    void missingLicenseFailsClosedWhenEnforced() {
        Tenant tenant = Tenant.create("Mortgage Contract", "mortgage-contract");
        var service = service(tenant, true, result(LicenseValidationStatus.ACTIVE,
                claims(tenant.id(), Set.of("DOCUMENT_PROCESSING"))));

        LicenseEnforcementException ex = assertThrows(LicenseEnforcementException.class,
                () -> service.requireDocumentProcessing(tenant.id(), ActorId.generate(), CorrelationId.generate()));
        assertEquals("LICENSE_REQUIRED", ex.errorCode());
    }

    @Test
    void localDisabledModeDoesNotRequireLicense() {
        Tenant tenant = Tenant.create("Local Demo", "local-demo-test");
        var service = service(tenant, false, result(LicenseValidationStatus.CONFIGURATION_ERROR, null));
        assertDoesNotThrow(() -> service.requireDocumentProcessing(
                tenant.id(), ActorId.generate(), CorrelationId.generate()));
        assertEquals(LicenseValidationStatus.NOT_ENFORCED, service.status(tenant.id()).status());
    }

    @Test
    void activeSignedLicenseRequiresDocumentProcessingEntitlement() {
        Tenant tenant = Tenant.create("Title", "title-department");
        ConfigurableVerifier verifier = new ConfigurableVerifier();
        verifier.result = result(LicenseValidationStatus.ACTIVE, claims(tenant.id(), Set.of("REPORTING")));
        InMemoryLicenseRepository licenses = new InMemoryLicenseRepository();
        TenantLicensingService service = service(tenant, true, verifier, licenses);

        ActorId actor = ActorId.generate();
        service.install(tenant.id(), "LV1.test.sig", actor, CorrelationId.generate());
        TenantLicenseStatus status = service.status(tenant.id());
        assertEquals(LicenseValidationStatus.ACTIVE, status.status());
        assertFalse(status.processingAllowed());
        assertEquals("DOCUMENT_PROCESSING_ENTITLEMENT_MISSING", status.reasonCode());
        assertThrows(LicenseEnforcementException.class,
                () -> service.requireDocumentProcessing(tenant.id(), actor, CorrelationId.generate()));
    }

    @Test
    void installAndReplaceKeepsHistoryWhileNewLicenseBecomesActive() {
        Tenant tenant = Tenant.create("Contract", "contract-department");
        ConfigurableVerifier verifier = new ConfigurableVerifier();
        InMemoryLicenseRepository licenses = new InMemoryLicenseRepository();
        TenantLicensingService service = service(tenant, true, verifier, licenses);
        ActorId actor = ActorId.generate();

        verifier.result = result(LicenseValidationStatus.ACTIVE, claims(tenant.id(), Set.of("DOCUMENT_PROCESSING")));
        UUID firstLicenseId = verifier.result.claims().licenseId();
        service.install(tenant.id(), "LV1.first.sig", actor, CorrelationId.generate());

        verifier.result = result(LicenseValidationStatus.ACTIVE, claims(tenant.id(), Set.of("DOCUMENT_PROCESSING", "REPORTING")));
        UUID secondLicenseId = verifier.result.claims().licenseId();
        service.install(tenant.id(), "LV1.second.sig", actor, CorrelationId.generate());

        assertTrue(service.status(tenant.id()).processingAllowed());
        assertEquals(secondLicenseId, service.status(tenant.id()).claims().licenseId());
        var history = service.history(tenant.id());
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(h -> h.licenseId().equals(firstLicenseId) && !h.active()));
        assertTrue(history.stream().anyMatch(h -> h.licenseId().equals(secondLicenseId) && h.active()));
    }


    @Test
    void inactiveHistoryKeepsVerifiedInstallMetadataWhenCurrentKeyNoLongerVerifiesToken() {
        Tenant tenant = Tenant.create("Contract", "contract-history-key-rotation");
        HistoricalVerifier verifier = new HistoricalVerifier();
        InMemoryLicenseRepository licenses = new InMemoryLicenseRepository();
        TenantLicensingService service = service(tenant, true, verifier, licenses);
        ActorId actor = ActorId.generate();

        LicenseClaims firstClaims = claims(tenant.id(), Set.of("DOCUMENT_PROCESSING"));
        verifier.current = result(LicenseValidationStatus.ACTIVE, firstClaims);
        verifier.persisted = firstClaims;
        service.install(tenant.id(), "LV1.first.sig", actor, CorrelationId.generate());

        LicenseClaims secondClaims = claims(tenant.id(), Set.of("DOCUMENT_PROCESSING", "REPORTING"));
        verifier.current = result(LicenseValidationStatus.ACTIVE, secondClaims);
        verifier.persisted = secondClaims;
        service.install(tenant.id(), "LV1.second.sig", actor, CorrelationId.generate());

        verifier.current = result(LicenseValidationStatus.INVALID_SIGNATURE, null);
        verifier.persisted = firstClaims;
        var firstRecordId = licenses.records.get(0).id();
        var history = service.history(tenant.id());
        var first = history.stream().filter(item -> item.recordId().equals(firstRecordId)).findFirst().orElseThrow();

        assertEquals("SUPERSEDED", first.status());
        assertEquals(firstClaims.licenseId(), first.claims().licenseId());
        assertEquals("VERIFIED_AT_INSTALL", first.verificationEvidence());
    }

    private static TenantLicensingService service(Tenant tenant, boolean enforced, LicenseVerificationResult result) {
        ConfigurableVerifier verifier = new ConfigurableVerifier();
        verifier.result = result;
        return service(tenant, enforced, verifier, new InMemoryLicenseRepository());
    }

    private static TenantLicensingService service(Tenant tenant, boolean enforced,
                                                   LicenseTokenVerifier verifier, InMemoryLicenseRepository licenses) {
        TenantRepository tenantRepository = new TenantRepository() {
            @Override public Optional<Tenant> findById(TenantId tenantId) { return tenant.id().equals(tenantId) ? Optional.of(tenant) : Optional.empty(); }
            @Override public Optional<Tenant> findBySlug(String slug) { return tenant.slug().equals(slug) ? Optional.of(tenant) : Optional.empty(); }
            @Override public List<Tenant> findAll() { return List.of(tenant); }
        };
        AuditPort auditPort = new AuditPort() { @Override public void record(AuditRecord auditRecord) { } };
        return new TenantLicensingService(licenses, tenantRepository, verifier, new AuditService(auditPort), enforced);
    }

    private static LicenseVerificationResult result(LicenseValidationStatus status, LicenseClaims claims) {
        return LicenseVerificationResult.of(status, claims, status.name());
    }

    private static LicenseClaims claims(TenantId tenantId, Set<String> entitlements) {
        Instant now = Instant.now();
        return new LicenseClaims(1, UUID.randomUUID(), tenantId, "Customer", "ENTERPRISE",
                now.minusSeconds(60), null, now.plusSeconds(3600), entitlements, Map.of());
    }

    private static final class ConfigurableVerifier implements LicenseTokenVerifier {
        LicenseVerificationResult result;
        @Override public LicenseVerificationResult verify(String signedToken, TenantId expectedTenantId, Instant now) { return result; }
        @Override public boolean isVerificationKeyConfigured() { return true; }
    }

    private static final class HistoricalVerifier implements LicenseTokenVerifier {
        LicenseVerificationResult current;
        LicenseClaims persisted;
        @Override public LicenseVerificationResult verify(String signedToken, TenantId expectedTenantId, Instant now) { return current; }
        @Override public boolean isVerificationKeyConfigured() { return true; }
        @Override public Optional<LicenseClaims> readPersistedVerifiedClaims(TenantLicenseRecord record) { return Optional.ofNullable(persisted); }
    }

    private static final class InMemoryLicenseRepository implements TenantLicenseRepository {
        final List<TenantLicenseRecord> records = new ArrayList<>();
        final Map<TenantId, UUID> active = new HashMap<>();
        @Override public TenantLicenseRecord save(TenantLicenseRecord record) { records.add(record); return record; }
        @Override public void activate(TenantId tenantId, UUID licenseRecordId, UUID actorId) { active.put(tenantId, licenseRecordId); }
        @Override public Optional<TenantLicenseRecord> findActive(TenantId tenantId) {
            UUID id = active.get(tenantId);
            return records.stream().filter(r -> r.id().equals(id)).findFirst();
        }
        @Override public List<TenantLicenseRecord> findHistory(TenantId tenantId) {
            return records.stream().filter(r -> r.tenantId().equals(tenantId)).toList();
        }
    }
}
