package com.securedoc.platform.application.intake;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.port.out.CasePriorityRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import com.securedoc.platform.infrastructure.intake.LocalWatchedFolderSourceAdapter;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0073: where a case's priority comes from, and who changes it. */
class CasePriorityTest {
    private final TenantId tenant = TenantId.generate();
    private final ActorId actor = ActorId.generate();
    private final CasePriorityRepository repository = mock(CasePriorityRepository.class);
    private final PackageRepository packages = mock(PackageRepository.class);
    private final List<AuditRecord> audits = new ArrayList<>();
    private final CasePriorityService service = new CasePriorityService(repository, packages, new AuditService(audits::add));

    @Test
    void onlyTheThreeNamesAreRead() {
        assertNull(CasePriorityService.read(null));
        assertNull(CasePriorityService.read(" "));
        assertEquals(PipelinePriority.URGENT, CasePriorityService.read(" urgent "));
        assertEquals(PipelinePriority.LOW, CasePriorityService.read("LOW"));
        for (String other : List.of("BULK", "HIGH", "1", "{}")) {
            assertThrows(ValidationException.class, () -> CasePriorityService.read(other));
        }
    }

    @Test
    void whatWasSentWinsThenTheSourcesDefaultThenNormal() {
        when(repository.sourceDefault(tenant, "src")).thenReturn(Optional.of(PipelinePriority.LOW));
        when(repository.sourceDefault(tenant, "other")).thenReturn(Optional.empty());
        PackageId pkg = PackageId.generate();
        assertEquals(PipelinePriority.URGENT, service.atIntake(tenant, pkg, PipelinePriority.URGENT, "src"));
        assertEquals(PipelinePriority.LOW, service.atIntake(tenant, pkg, null, "src"));
        assertEquals(PipelinePriority.NORMAL, service.atIntake(tenant, pkg, null, "other"));
        assertEquals(PipelinePriority.NORMAL, service.atIntake(tenant, pkg, null, null));
        verify(repository).saveNew(tenant, pkg, PipelinePriority.URGENT);
    }

    @Test
    void aChangeIsAuditedOnceAndAnotherEntitysCaseIsNotFound() {
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, actor, PackageMetadata.empty());
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(repository.change(any(), any(), any())).thenReturn(PipelinePriority.NORMAL, PipelinePriority.URGENT);
        service.change(tenant, actor, pkg.id(), PipelinePriority.URGENT, CorrelationId.generate());
        service.change(tenant, actor, pkg.id(), PipelinePriority.URGENT, CorrelationId.generate());
        assertEquals(1, audits.size(), "no change, no record");
        assertEquals(AuditAction.CASE_PRIORITY_CHANGED, audits.get(0).action());
        assertEquals("priority=NORMAL->URGENT", audits.get(0).detail());
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class,
                () -> service.change(tenant, actor, pkg.id(), PipelinePriority.LOW, CorrelationId.generate()));
    }

    @Test
    void theOtherSettersOfACommandKeepItsPriority() {
        var command = CreatePackageCommand.of(PackageType.MANUAL_UPLOAD, Map.of(), Map.of(), CorrelationId.generate())
                .withPriority(PipelinePriority.URGENT, "src")
                .withCaseSensitivity(com.securedoc.platform.domain.document.SensitivityLevel.Received.standard());
        assertEquals(PipelinePriority.URGENT, command.priority());
        assertEquals("src", command.sourceKey());
    }

    @Test
    void theTriggerFileCanHoldAPriorityAndAWrongOneIsAMalformedTrigger(@TempDir Path root) throws Exception {
        WatchedFolderProperties properties = new WatchedFolderProperties();
        properties.setRootPath(root.toString());
        properties.setEnabled(true);
        drop(root, "a", "{\"businessIdentifiers\":{},\"priority\":\"LOW\"}");
        drop(root, "b", "{\"businessIdentifiers\":{},\"priority\":\"SOON\"}");
        drop(root, "c", "{\"businessIdentifiers\":{}}");
        var found = new LocalWatchedFolderSourceAdapter(properties).discover();
        assertEquals(3, found.size());
        for (var candidate : found) {
            switch (candidate.sourceReference()) {
                case "a" -> assertEquals(PipelinePriority.LOW, candidate.priority());
                case "b" -> assertFalse(candidate.manifestValid());
                default -> {
                    assertTrue(candidate.manifestValid());
                    assertNull(candidate.priority());
                }
            }
        }
    }

    private static void drop(Path root, String name, String manifest) throws Exception {
        Path folder = Files.createDirectories(root.resolve(name));
        Files.writeString(folder.resolve("manifest.json"), manifest);
        Files.writeString(folder.resolve("one.pdf"), "%PDF-1.7");
    }
}
