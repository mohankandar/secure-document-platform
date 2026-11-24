package com.securedoc.platform.api.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.CreatePackageRequest;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.intake.command.CreatePackageResult;
import com.securedoc.platform.application.intake.query.PackageDetail;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0021: New case's "Sensitivity" choice, and the case panel's read-only levels. */
class PackageSensitivityApiTest {
    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

    private CreatePackageCommand createWith(String json) throws Exception {
        CreatePackageUseCase create = mock(CreatePackageUseCase.class);
        when(create.execute(any(), any())).thenReturn(CreatePackageResult.of(PackageId.generate(),
                BusinessRecordId.generate(), PackageStatus.CREATED, Instant.now()));
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(),
                TenantId.generate(), "person", Set.of("DOCUMENT_PROCESSOR")));
        when(context.resolveCorrelationId(request)).thenReturn(CorrelationId.generate());
        PackageController controller = new PackageController(create, mock(UploadDocumentUseCase.class),
                mock(SubmitPackageUseCase.class), mock(PackageProcessingDispatcher.class),
                mock(GetPackageQuery.class), mock(ListPackagesQuery.class), context);
        controller.createPackage(mapper.readValue(json, CreatePackageRequest.class), request);
        ArgumentCaptor<CreatePackageCommand> command = ArgumentCaptor.forClass(CreatePackageCommand.class);
        verify(create).execute(command.capture(), any());
        return command.getValue();
    }

    @Test
    void newCaseAppliesTheChosenLevel() throws Exception {
        for (SensitivityLevel level : SensitivityLevel.values()) {
            CreatePackageCommand command = createWith(
                    "{\"packageType\":\"MANUAL_UPLOAD\",\"sensitivityLevel\":\"" + level.name() + "\"}");
            assertEquals(new SensitivityLevel.Received(level, false), command.caseSensitivity());
        }
    }

    @Test
    void newCaseWithoutAChoiceIsStandard() throws Exception {
        assertEquals(SensitivityLevel.Received.standard(), createWith("{\"packageType\":\"MANUAL_UPLOAD\"}").caseSensitivity());
        assertEquals(SensitivityLevel.Received.standard(),
                createWith("{\"packageType\":\"MANUAL_UPLOAD\",\"sensitivityLevel\":null}").caseSensitivity());
    }

    @Test
    void anyOtherValueIsTakenAsStandardWithTheWarningNotRefused() throws Exception {
        for (String sent : List.of("\"confidential\"", "\"SECRET\"", "\"\"", "1", "true", "{\"a\":1}", "[\"CONFIDENTIAL\"]")) {
            CreatePackageCommand command = createWith(
                    "{\"packageType\":\"MANUAL_UPLOAD\",\"sensitivityLevel\":" + sent + "}");
            assertEquals(new SensitivityLevel.Received(SensitivityLevel.STANDARD, true), command.caseSensitivity(), sent);
        }
    }

    @Test
    void theCasePanelGetsTheCaseLevelTheEffectiveLevelAndEachDocumentLevel() {
        GetPackageQuery query = mock(GetPackageQuery.class);
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "p", Set.of());
        when(context.resolvePrincipal(request)).thenReturn(principal);
        PackageId id = PackageId.generate();
        Instant now = Instant.now();
        when(query.execute(eq(id), eq(principal))).thenReturn(new PackageDetail(id, PackageType.API_SUBMISSION,
                PackageStatus.SUBMITTED, List.of(), Map.of(), List.of(
                new PackageDetail.DocumentSummary(DocumentId.generate(), "a.pdf", "application/pdf", 1, DocumentType.PDF,
                        DocumentStatus.UPLOADED, SensitivityLevel.STANDARD),
                new PackageDetail.DocumentSummary(DocumentId.generate(), "b.pdf", "application/pdf", 1, DocumentType.PDF,
                        DocumentStatus.UPLOADED, SensitivityLevel.HIGHLY_CONFIDENTIAL)),
                now, now, SensitivityLevel.CONFIDENTIAL, null));
        PackageController controller = new PackageController(mock(CreatePackageUseCase.class),
                mock(UploadDocumentUseCase.class), mock(SubmitPackageUseCase.class),
                mock(PackageProcessingDispatcher.class), query, mock(ListPackagesQuery.class), context);

        var body = controller.getPackageDetail(id.toString(), request).getBody();

        assertEquals("CONFIDENTIAL", body.sensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", body.effectiveSensitivityLevel());
        assertEquals("STANDARD", body.documents().get(0).sensitivityLevel());
        assertEquals("CONFIDENTIAL", body.documents().get(0).effectiveSensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", body.documents().get(1).sensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", body.documents().get(1).effectiveSensitivityLevel());
    }
}
