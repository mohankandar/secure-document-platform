package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0038: a stopped case in the Cases list carries why it stopped, as an LV code and nothing else. */
class ListPackagesStopReasonTest {
    private final TenantId tenant = TenantId.generate();
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant, "p", Set.of());
    private final PackageRepository packages = mock(PackageRepository.class);
    private final ProcessingIncidentRepository incidents = mock(ProcessingIncidentRepository.class);
    private final ListPackagesQueryService service = new ListPackagesQueryService(packages,
            mock(BusinessIdentityService.class), incidents);

    @Test
    void aStoppedCaseCarriesItsIncidentsCodeAndOtherCasesNone() {
        DocumentPackage stopped = pkg(PackageStatus.FAILED);
        DocumentPackage mapped = pkg(PackageStatus.FAILED);
        DocumentPackage odd = pkg(PackageStatus.FAILED);
        DocumentPackage reviewing = pkg(PackageStatus.IN_REVIEW);
        when(packages.findByTenantId(eq(tenant), anyInt(), anyInt())).thenReturn(List.of(stopped, mapped, odd, reviewing));
        incident(stopped, "OCR_TIMEOUT", Map.of("lvCode", "LV-PRC-004", "page", "14"));
        incident(mapped, "OCR_TIMEOUT", Map.of());
        incident(odd, "Jane Doe 123-45-6789", Map.of("lvCode", "Jane Doe"));

        var rows = service.execute(principal, 0, 20);

        assertEquals("LV-PRC-004", rows.get(0).stopReasonCode(), "the incident's own code");
        assertNotNull(rows.get(1).stopReasonCode(), "else the code its reason maps to");
        assertTrue(rows.get(1).stopReasonCode().startsWith("LV-"));
        assertNull(rows.get(2).stopReasonCode(), "text that is no code is never passed on");
        assertNull(rows.get(3).stopReasonCode(), "only a stopped case");
        verify(incidents, never()).findLatestByPackageIdAndTenantId(reviewing.id(), tenant);
    }

    private DocumentPackage pkg(PackageStatus status) {
        DocumentPackage pkg = mock(DocumentPackage.class);
        when(pkg.id()).thenReturn(PackageId.generate());
        when(pkg.status()).thenReturn(status);
        when(pkg.packageType()).thenReturn(PackageType.MANUAL_UPLOAD);
        when(pkg.businessRecordId()).thenReturn(Optional.empty());
        when(pkg.createdAt()).thenReturn(Instant.now());
        when(pkg.updatedAt()).thenReturn(Instant.now());
        return pkg;
    }

    private void incident(DocumentPackage pkg, String reason, Map<String, String> meta) {
        ProcessingIncident incident = mock(ProcessingIncident.class);
        when(incident.reasonCode()).thenReturn(reason);
        when(incident.safeMetadata()).thenReturn(meta);
        when(incidents.findLatestByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(incident));
    }
}
