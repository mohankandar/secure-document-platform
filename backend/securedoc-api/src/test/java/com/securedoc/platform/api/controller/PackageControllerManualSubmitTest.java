package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.PackageDetail;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageControllerManualSubmitTest {

    @Test
    void manualSubmitUsesPostCommitDispatcherInsteadOfRunningProcessingInline() throws Exception {
        CreatePackageUseCase create = mock(CreatePackageUseCase.class);
        UploadDocumentUseCase upload = mock(UploadDocumentUseCase.class);
        SubmitPackageUseCase submit = mock(SubmitPackageUseCase.class);
        PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
        GetPackageQuery getPackage = mock(GetPackageQuery.class);
        ListPackagesQuery listPackages = mock(ListPackagesQuery.class);
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        PackageId packageId = PackageId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        TenantId tenantId = TenantId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        ActorId actorId = ActorId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        CorrelationId correlationId = CorrelationId.of(
                UUID.fromString("44444444-4444-4444-4444-444444444444"));
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
                actorId, tenantId, "manual-intake-test", Set.of("DOCUMENT_PROCESSOR"));

        when(context.resolvePrincipal(request)).thenReturn(principal);
        when(context.resolveCorrelationId(request)).thenReturn(correlationId);
        when(getPackage.execute(eq(packageId), eq(principal))).thenReturn(new PackageDetail(
                packageId,
                PackageType.MANUAL_UPLOAD,
                PackageStatus.SUBMITTED,
                List.of(),
                Map.of(),
                List.of(),
                Instant.parse("2026-09-04T12:00:00Z"),
                Instant.parse("2026-09-04T12:00:00Z")));

        PackageController controller = new PackageController(
                create, upload, submit, dispatcher, getPackage, listPackages, context);

        var response = controller.submitPackage(packageId.toString(), request);

        verify(submit).execute(any(), eq(principal));
        ArgumentCaptor<TriggerPackageProcessingCommand> dispatched =
                ArgumentCaptor.forClass(TriggerPackageProcessingCommand.class);
        verify(dispatcher).dispatch(dispatched.capture());
        assertEquals(packageId, dispatched.getValue().packageId());
        assertEquals(tenantId, dispatched.getValue().tenantId());
        assertEquals(actorId, dispatched.getValue().actorId());
        assertEquals(correlationId, dispatched.getValue().correlationId());
        assertEquals("SUBMITTED", response.getBody().status());

        Method submitMethod = PackageController.class.getMethod(
                "submitPackage", String.class, HttpServletRequest.class);
        assertNotNull(submitMethod.getAnnotation(Transactional.class),
                "Dispatcher relies on an active transaction so processing starts only after commit");
    }
}
