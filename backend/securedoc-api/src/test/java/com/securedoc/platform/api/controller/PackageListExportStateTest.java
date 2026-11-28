package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.PackageSummary;
import com.securedoc.platform.application.pipeline.ExportJobService;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0029: the Cases list (GET /packages) carries each case's export state for the "Exporting" status. */
class PackageListExportStateTest {

    @Test
    void eachCaseCarriesItsExportStateAndACaseWithoutJobsIsNone() {
        TenantId tenant = TenantId.generate();
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(), tenant, "person",
                Set.of("DOCUMENT_PROCESSOR")));
        PackageId waiting = PackageId.generate();
        PackageId plain = PackageId.generate();
        PackageId stopped = PackageId.generate();
        Instant now = Instant.now();
        ListPackagesQuery list = mock(ListPackagesQuery.class);
        when(list.execute(any(), anyInt(), anyInt())).thenReturn(List.of(
                new PackageSummary(waiting, PackageType.MANUAL_UPLOAD, PackageStatus.IN_REVIEW, List.of(), 1, now, now),
                new PackageSummary(plain, PackageType.MANUAL_UPLOAD, PackageStatus.PROCESSING, List.of(), 1, now, now),
                new PackageSummary(stopped, PackageType.MANUAL_UPLOAD, PackageStatus.FAILED, List.of(), 1, now, now,
                        "LV-PRC-002")));
        ExportJobService exportJobs = mock(ExportJobService.class);
        // Task 0038: the list reads the rows (state, reason code, destination name) instead of the states alone.
        when(exportJobs.caseExportRows(eq(tenant), any())).thenReturn(Map.of(waiting, new ExportJobService.CaseExportRow(
                ExportJobService.CaseExportState.WAITING_FOR_YOU, "LV-DLV-004", "Records archive")));

        PackageController controller = new PackageController(mock(CreatePackageUseCase.class),
                mock(UploadDocumentUseCase.class), mock(SubmitPackageUseCase.class),
                mock(PackageProcessingDispatcher.class), mock(GetPackageQuery.class), list, context, exportJobs);
        var body = controller.listPackages(0, 20, false, request).getBody();
        // Task 0111: the dashboard's list is its own query (hidden cases left out, stable order).
        org.mockito.Mockito.verify(list, org.mockito.Mockito.never()).executeForDashboard(any(), anyInt(), anyInt());
        assertEquals(0, controller.listPackages(0, 100, true, request).getBody().size(), "the dashboard query answers");
        org.mockito.Mockito.verify(list).executeForDashboard(any(), eq(0), eq(100));

        assertEquals("WAITING_FOR_YOU", body.get(0).exportState());
        assertEquals("NONE", body.get(1).exportState());
        assertEquals("LV-DLV-004", body.get(0).stopReasonCode());
        assertEquals("Records archive", body.get(0).destinationName());
        assertNull(body.get(1).stopReasonCode());
        assertNull(body.get(1).destinationName());
        assertEquals("LV-PRC-002", body.get(2).stopReasonCode(), "a stopped case: why it stopped");
        verify(exportJobs, times(1)).caseExportRows(eq(tenant), eq(List.of(waiting, plain, stopped)));
    }

    @Test
    void withoutTheExportServiceEveryCaseIsNone() {
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(),
                TenantId.generate(), "person", Set.of("DOCUMENT_PROCESSOR")));
        Instant now = Instant.now();
        ListPackagesQuery list = mock(ListPackagesQuery.class);
        when(list.execute(any(), anyInt(), anyInt())).thenReturn(List.of(
                new PackageSummary(PackageId.generate(), PackageType.MANUAL_UPLOAD, PackageStatus.COMPLETED, List.of(), 1,
                        now, now)));
        PackageController controller = new PackageController(mock(CreatePackageUseCase.class),
                mock(UploadDocumentUseCase.class), mock(SubmitPackageUseCase.class),
                mock(PackageProcessingDispatcher.class), mock(GetPackageQuery.class), list, context);
        assertEquals("NONE", controller.listPackages(0, 20, false, request).getBody().get(0).exportState());
    }
}
