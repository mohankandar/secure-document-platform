package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0111 item 3: a case without documents is closed as expired after the set time; nothing else is touched. */
class EmptyCaseExpiryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private final TenantId tenant = TenantId.generate();
    private final ActorId system = ActorId.generate();

    private DocumentPackage pkg(PackageType type, PackageStatus status, Instant created) {
        return DocumentPackage.reconstitute(PackageId.generate(), tenant, type, null, null, null, system, created, status,
                PackageMetadata.empty(), List.of(), created);
    }

    @Test
    void closesOnlyEmptyCasesOlderThanTheSetTimeAndHidesThem() {
        DocumentPackage old = pkg(PackageType.MANUAL_UPLOAD, PackageStatus.CREATED, NOW.minus(Duration.ofDays(11)));
        DocumentPackage recent = pkg(PackageType.MANUAL_UPLOAD, PackageStatus.CREATED, NOW.minus(Duration.ofDays(6)));
        DocumentPackage machine = pkg(PackageType.API_SUBMISSION, PackageStatus.CREATED, NOW.minus(Duration.ofDays(30)));
        DocumentPackage working = pkg(PackageType.FOLDER_INTAKE, PackageStatus.PROCESSING, NOW.minus(Duration.ofDays(30)));
        Fake packages = new Fake(List.of(old, recent, machine, working));
        List<PackageId> hidden = new ArrayList<>();
        List<AuditRecord> audits = new ArrayList<>();
        EmptyCaseExpiryService service = new EmptyCaseExpiryService(packages, (id, t, a, at) -> hidden.add(id),
                new AuditService(audits::add), Duration.ofDays(7));

        int closed = service.expireDue(NOW, 100, system, CorrelationId.generate());

        assertEquals(1, closed);
        assertEquals(PackageStatus.CANCELLED, old.status());
        assertEquals(List.of(old.id()), hidden, "off the dashboard");
        assertEquals(List.of(old.id()), packages.saved);
        assertEquals(PackageStatus.CREATED, recent.status(), "younger than 7 days");
        assertEquals(PackageStatus.CREATED, machine.status(), "a machine intake has its own expiry");
        assertEquals(PackageStatus.PROCESSING, working.status());
        assertEquals(1, audits.size(), "kept in the audit trail for Reports");
    }

    @Test
    void refusesANonPositiveTime() {
        assertThrows(IllegalArgumentException.class, () -> new EmptyCaseExpiryService(new Fake(List.of()),
                (id, t, a, at) -> { }, new AuditService(r -> { }), Duration.ZERO));
    }

    private static final class Fake implements PackageRepository {
        private final List<DocumentPackage> all;
        final List<PackageId> saved = new ArrayList<>();
        Fake(List<DocumentPackage> all) { this.all = all; }
        public void save(DocumentPackage p) { saved.add(p.id()); }
        public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId t) { return all.stream().filter(p -> p.id().equals(id)).findFirst(); }
        public List<DocumentPackage> findByTenantId(TenantId t, int offset, int limit) { return all; }
        public long countByTenantId(TenantId t) { return all.size(); }
        public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId t, String f) { return Optional.empty(); }
        public List<DocumentPackage> findByStatus(PackageStatus s, int limit) { return List.of(); }
        // Deliberately returns every case: the service must check each candidate itself.
        @Override public List<DocumentPackage> findEmptyCasesCreatedBefore(Instant cutoff, int limit) { return all; }
    }
}
