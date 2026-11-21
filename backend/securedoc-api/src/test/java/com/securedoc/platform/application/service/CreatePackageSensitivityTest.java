package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0021: the case level is stored as sent, audited as a code, and an unknown one is LV-INT-005 (not refused). */
class CreatePackageSensitivityTest {

    private record Run(DocumentPackage saved, List<AuditRecord> audits) { }

    private Run create(SensitivityLevel.Received received) {
        PackageRepository packages = mock(PackageRepository.class);
        BusinessIdentityService identity = mock(BusinessIdentityService.class);
        when(identity.resolveOrCreate(any(), anyMap()))
                .thenReturn(new BusinessIdentitySnapshot(BusinessRecordId.generate(), false, List.of()));
        List<AuditRecord> audits = new ArrayList<>();
        CreatePackageService service = new CreatePackageService(mock(TenantContextService.class),
                mock(CapabilityEnforcementService.class), identity, mock(TenantLicensingService.class), packages,
                mock(DomainEventPublisher.class), new AuditService(audits::add));
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "person", Set.of());
        service.execute(CreatePackageCommand.of(PackageType.MANUAL_UPLOAD, Map.of("contractNumber", "C-1"), Map.of(),
                CorrelationId.generate()).withCaseSensitivity(received), principal);
        ArgumentCaptor<DocumentPackage> saved = ArgumentCaptor.forClass(DocumentPackage.class);
        verify(packages).save(saved.capture());
        return new Run(saved.getValue(), audits);
    }

    private static Optional<AuditRecord> first(List<AuditRecord> audits, AuditAction action) {
        return audits.stream().filter(r -> r.action() == action).findFirst();
    }

    @Test
    void eachValidLevelIsStoredAndAuditedAsItsCode() {
        for (SensitivityLevel level : SensitivityLevel.values()) {
            Run run = create(SensitivityLevel.fromSource(level.name()));
            assertEquals(level, run.saved().sensitivityLevel());
            assertTrue(first(run.audits(), AuditAction.PACKAGE_CREATED).orElseThrow().detail()
                    .endsWith("sensitivityLevel=" + level.name()));
            assertTrue(first(run.audits(), AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN).isEmpty());
        }
    }

    @Test
    void nothingSentIsStandard() {
        Run run = create(SensitivityLevel.fromSource(null));
        assertEquals(SensitivityLevel.STANDARD, run.saved().sensitivityLevel());
        assertTrue(first(run.audits(), AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN).isEmpty());
    }

    @Test
    void anUnknownValueIsTakenAsStandardWithTheWarningCodeOnly() {
        Run run = create(SensitivityLevel.fromSource("Highly Confidential"));
        assertEquals(SensitivityLevel.STANDARD, run.saved().sensitivityLevel());
        AuditRecord warning = first(run.audits(), AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN).orElseThrow();
        assertEquals("code=LV-INT-005,scope=CASE", warning.detail());
        assertEquals(run.saved().id().toString(), warning.resourceId());
        assertTrue(run.audits().stream().noneMatch(r -> r.detail() != null && r.detail().contains("Highly Confidential")));
    }
}
