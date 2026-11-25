package com.securedoc.platform.api.intake;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.PackageController;
import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import com.securedoc.platform.api.intake.external.ExternalIntakeController;
import com.securedoc.platform.api.security.CapabilityAuthorizationInterceptor;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.IntakeBusyException;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Task 0081: a refused push or upload is counted by source system and code, and the answer stays the same. */
class IntakeRefusalsTest {
    private static final String PUSH = "/api/v1/external-intakes";
    private static final String MANUAL = "/api/v1/packages";
    private static final String BODY = """
            {"externalSourcePackageId":"P-1","sourceRevision":"1","documents":[{"externalSourceDocumentId":"D-1","fileName":"a.pdf"}]}""";
    private static final String MANUAL_BODY = "{\"packageType\":\"MANUAL_UPLOAD\",\"businessIdentifiers\":{},\"metadata\":{}}";

    private final TenantId tenant = TenantId.generate();
    private final IntegrationConnectionId connection = IntegrationConnectionId.generate();
    private final List<String> counted = new ArrayList<>();
    private final ExternalIntakeService intake = mock(ExternalIntakeService.class);
    private final CreatePackageUseCase create = mock(CreatePackageUseCase.class);
    private final RequestContextResolver context = mock(RequestContextResolver.class);
    private final IntegrationConnectionService connections = mock(IntegrationConnectionService.class);
    private String role = "DOCUMENT_PROCESSOR";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(context.resolveIntegrationPrincipal(any())).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(), tenant, "client", Set.of()));
        when(context.resolvePrincipal(any())).thenAnswer(call ->
                AuthenticatedPrincipal.of(ActorId.generate(), tenant, "person", Set.of(role)));
        when(context.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        IntegrationConnection source = mock(IntegrationConnection.class);
        when(source.id()).thenReturn(connection);
        when(connections.resolveEnabledPushSource(tenant, "client")).thenReturn(source);
        PackageController packages = new PackageController(create, mock(UploadDocumentUseCase.class), mock(SubmitPackageUseCase.class),
                mock(PackageProcessingDispatcher.class), mock(GetPackageQuery.class), mock(ListPackagesQuery.class), context);
        mvc = MockMvcBuilders.standaloneSetup(new ExternalIntakeController(intake, context), packages)
                .addInterceptors(new CapabilityAuthorizationInterceptor(context, new ProductAuthorizationPolicy(), new AuditService(r -> { })))
                .setControllerAdvice(new GlobalExceptionHandler(), new IntakeRefusals(context, connections,
                        (t, s, code) -> {
                            assertEquals(tenant, t);
                            counted.add(s + " " + code.code());
                        }))
                .build();
    }

    private int push(RuntimeException refused) throws Exception {
        doThrow(refused).when(intake).createPushIntake(any(), any(), any());
        return mvc.perform(post(PUSH).servletPath(PUSH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus();
    }

    private void upload() throws Exception {
        mvc.perform(post(MANUAL).servletPath(MANUAL).contentType(MediaType.APPLICATION_JSON).content(MANUAL_BODY));
    }

    @Test
    void aRefusedPushIsCountedForItsConnection() throws Exception {
        assertEquals(409, push(new IntakeConflictException("SOURCE_REVISION_REUSED", "x")));
        assertEquals(1, counted.size());
        assertTrue(counted.get(0).startsWith(connection.value() + " LV-INT-"), counted.get(0));
        assertEquals(400, push(new IntakeValidationException(IntakeRejectionCode.DUPLICATE_INTAKE, "x")));
        assertEquals(connection.value() + " LV-INT-017", counted.get(1));
    }

    @Test
    void aBusyAnswerAnAddressAndACallerWhoMayNotAreNotCounted() throws Exception {
        assertEquals(429, push(new IntakeBusyException(60)));
        assertEquals(403, push(new AccessDeniedException("x")));
        mvc.perform(get(PUSH + "/not-an-id").servletPath(PUSH + "/not-an-id"));
        assertEquals(List.of(), counted);
    }

    @Test
    void aCallerWithoutASourceSystemIsNotCountedAndGetsTheSameAnswer() throws Exception {
        when(connections.resolveEnabledPushSource(tenant, "client")).thenThrow(new AccessDeniedException("x"));
        assertEquals(409, push(new IntakeConflictException("SOURCE_REVISION_REUSED", "x")));
        assertEquals(List.of(), counted);
    }

    @Test
    void aRefusedUploadIsCountedForManualUploadAndNotForSomeoneWhoMayNot() throws Exception {
        when(create.execute(any(), any())).thenThrow(new IntakeValidationException(IntakeRejectionCode.MISSING_REQUIRED_IDENTIFIER, "x"));
        upload();
        assertEquals(1, counted.size());
        assertEquals("type:MANUAL_UPLOAD", counted.get(0).split(" ")[0]);
        role = "REPORT_VIEWER";
        upload();
        assertEquals(1, counted.size());
    }
}
