package com.securedoc.platform.api.audit;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.AuditController;
import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditControllerRoutineSuppressionTest {

    @Test
    void defaultTimelineExcludesRoutineNoOpScansBeforeQueryLimit() {
        var query = mock(ListAuditRecordsQuery.class);
        var resolver = mock(RequestContextResolver.class);
        var request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal());
        when(query.execute(any())).thenReturn(List.of());

        var controller = new AuditController(query, resolver, mock(AuditService.class));
        controller.listAuditRecords(null, null, null, null, null, null, null,
                null, null, 50, false, request);

        var captor = ArgumentCaptor.forClass(AuditRecordFilter.class);
        verify(query).execute(captor.capture());
        assertFalse(captor.getValue().includeRoutineNoOpScans());
    }

    @Test
    void explicitWatchedFolderActionStillIncludesRoutineScans() {
        var query = mock(ListAuditRecordsQuery.class);
        var resolver = mock(RequestContextResolver.class);
        var request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal());
        when(query.execute(any())).thenReturn(List.of());

        var controller = new AuditController(query, resolver, mock(AuditService.class));
        controller.listAuditRecords(null, "WATCHED_FOLDER_SCAN_TRIGGERED", null, null, null, null, null,
                null, null, 50, false, request);

        var captor = ArgumentCaptor.forClass(AuditRecordFilter.class);
        verify(query).execute(captor.capture());
        assertTrue(captor.getValue().includeRoutineNoOpScans());
    }

    @Test
    void showRoutineActivityExplicitlyIncludesRoutineScans() {
        var query = mock(ListAuditRecordsQuery.class);
        var resolver = mock(RequestContextResolver.class);
        var request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal());
        when(query.execute(any())).thenReturn(List.of());

        var controller = new AuditController(query, resolver, mock(AuditService.class));
        controller.listAuditRecords(null, null, null, null, null, null, null,
                null, null, 50, true, request);

        var captor = ArgumentCaptor.forClass(AuditRecordFilter.class);
        verify(query).execute(captor.capture());
        assertTrue(captor.getValue().includeRoutineNoOpScans());
    }


    @Test
    void securityPresetIsAppliedInsideTenantScopedQuery() {
        var query = mock(ListAuditRecordsQuery.class);
        var resolver = mock(RequestContextResolver.class);
        var request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal());
        when(query.execute(any())).thenReturn(List.of());

        var controller = new AuditController(query, resolver, mock(AuditService.class));
        controller.listAuditRecords("SECURITY", null, null, null, null, null, null,
                null, null, 50, false, request);

        var captor = ArgumentCaptor.forClass(AuditRecordFilter.class);
        verify(query).execute(captor.capture());
        assertTrue(captor.getValue().actions().contains("ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED"));
        assertTrue(captor.getValue().actions().contains("AUTHORIZATION_FAILED"));
    }

    @Test
    void fromAndToArePassedAsTypedBounds() {
        var query = mock(ListAuditRecordsQuery.class);
        var resolver = mock(RequestContextResolver.class);
        var request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal());
        when(query.execute(any())).thenReturn(List.of());

        var controller = new AuditController(query, resolver, mock(AuditService.class));
        controller.listAuditRecords(null, null, null, null, null, null, null,
                "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z", 50, false, request);

        var captor = ArgumentCaptor.forClass(AuditRecordFilter.class);
        verify(query).execute(captor.capture());
        assertTrue(captor.getValue().fromOpt().isPresent());
        assertTrue(captor.getValue().toOpt().isPresent());
        assertFalse(captor.getValue().fromOpt().orElseThrow().isAfter(captor.getValue().toOpt().orElseThrow()));
    }

    private AuthenticatedPrincipal principal() {
        return AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "audit-admin", Set.of("PLATFORM_OPERATIONS_ADMIN"));
    }
}
