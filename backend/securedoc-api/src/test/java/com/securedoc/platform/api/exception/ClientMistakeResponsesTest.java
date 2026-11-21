package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.PackageController;
import com.securedoc.platform.api.intake.external.ExternalIntakeController;
import com.securedoc.platform.api.intake.external.ScannerIntakeController;
import com.securedoc.platform.api.intake.external.ScannerIntakeTransactionBoundary;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0023 D1 (#7, #32): a client mistake on the intake endpoints is a 400 with a clear message, as
 * EXTERNAL_INGESTION_V1.md ("Error classes") promises, never a 500. Messages name LexaVault's own header, part or
 * field, never a value the client sent.
 */
class ClientMistakeResponsesTest {
    private static final String PUSH_UPLOAD = "/api/v1/external-intakes/" + UUID.randomUUID() + "/documents/D-1";
    private static final String SCANNER_UPLOAD = "/api/v1/scanner-intakes/" + UUID.randomUUID() + "/documents/D-1";
    private static final String SHA = "a".repeat(64);

    private MockMvc mvc;
    private ExternalIntakeService intake;

    @BeforeEach
    void setUp() {
        intake = mock(ExternalIntakeService.class);
        RequestContextResolver context = mock(RequestContextResolver.class);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "client",
                Set.of("DOCUMENT_PROCESSOR"));
        when(context.resolveIntegrationPrincipal(any(HttpServletRequest.class))).thenReturn(principal);
        when(context.resolvePrincipal(any(HttpServletRequest.class))).thenReturn(principal);
        when(context.resolveCorrelationId(any(HttpServletRequest.class))).thenReturn(CorrelationId.generate());
        PackageController packages = new PackageController(mock(CreatePackageUseCase.class),
                mock(UploadDocumentUseCase.class), mock(SubmitPackageUseCase.class),
                mock(PackageProcessingDispatcher.class), mock(GetPackageQuery.class), mock(ListPackagesQuery.class),
                context);
        mvc = MockMvcBuilders.standaloneSetup(new ExternalIntakeController(intake, context),
                        new ScannerIntakeController(mock(ScannerIntakeTransactionBoundary.class), context), packages)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String errorCode(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("errorCode").asText();
    }

    private static String message(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("message").asText();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{'%', 'P', 'D', 'F'});
    }

    @Test
    void aMissingIdempotencyKeyIs400NamingTheHeader() throws Exception {
        MvcResult result = mvc.perform(multipart(PUSH_UPLOAD).file(pdf()).header("X-Content-SHA256", SHA)).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("MISSING_HEADER", errorCode(result));
        assertEquals("Required request header 'Idempotency-Key' is missing", message(result));
        verifyNoInteractions(intake);
    }

    @Test
    void aMissingContentShaIs400NamingTheHeaderOnPushAndScanner() throws Exception {
        MvcResult push = mvc.perform(multipart(PUSH_UPLOAD).file(pdf()).header("Idempotency-Key", "k-1")).andReturn();
        assertEquals(400, push.getResponse().getStatus());
        assertEquals("Required request header 'X-Content-SHA256' is missing", message(push));

        MvcResult scanner = mvc.perform(post(SCANNER_UPLOAD).contentType(MediaType.APPLICATION_PDF)
                .content(new byte[]{1}).header("Idempotency-Key", "k-1")).andReturn();
        assertEquals(400, scanner.getResponse().getStatus());
        assertEquals("MISSING_HEADER", errorCode(scanner));
    }

    @Test
    void aMissingFilePartIs400() throws Exception {
        MvcResult result = mvc.perform(multipart(PUSH_UPLOAD).header("Idempotency-Key", "k-1")
                .header("X-Content-SHA256", SHA)).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("MISSING_FILE_PART", errorCode(result));
        assertEquals("Required multipart part 'file' is missing", message(result));

        MvcResult manual = mvc.perform(multipart("/api/v1/packages/" + UUID.randomUUID() + "/documents")).andReturn();
        assertEquals(400, manual.getResponse().getStatus());
        assertEquals("MISSING_FILE_PART", errorCode(manual));
    }

    @Test
    void aWrongContentTypeIs400WithoutEchoingTheTypeSent() throws Exception {
        MvcResult result = mvc.perform(post(SCANNER_UPLOAD).contentType("text/x-secret-value").content("x")
                .header("Idempotency-Key", "k-1").header("X-Content-SHA256", SHA)).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errorCode(result));
        assertTrue(message(result).contains("application/pdf"));
        assertFalse(message(result).contains("secret"));
    }

    @Test
    void aNullIdentifierValueOrDocumentEntryIs400NotA500() throws Exception {
        String nullIdentifier = "{\"externalSourcePackageId\":\"S-1\",\"sourceRevision\":\"1\","
                + "\"businessIdentifiers\":{\"loanNumber\":null},\"documents\":[{\"externalSourceDocumentId\":\"D-1\"}]}";
        MvcResult identifiers = mvc.perform(post("/api/v1/external-intakes").contentType(MediaType.APPLICATION_JSON)
                .content(nullIdentifier)).andReturn();
        assertEquals(400, identifiers.getResponse().getStatus());
        assertEquals("VALIDATION_ERROR", errorCode(identifiers));
        assertEquals("businessIdentifiers must not contain null values", message(identifiers));

        String nullMetadata = "{\"externalSourcePackageId\":\"S-1\",\"sourceRevision\":\"1\",\"metadata\":{\"k\":null},"
                + "\"documents\":[{\"externalSourceDocumentId\":\"D-1\"}]}";
        MvcResult metadata = mvc.perform(post("/api/v1/scanner-intakes").contentType(MediaType.APPLICATION_JSON)
                .content(nullMetadata)).andReturn();
        assertEquals(400, metadata.getResponse().getStatus());
        assertEquals("metadata must not contain null values", message(metadata));

        String nullDocument = "{\"externalSourcePackageId\":\"S-1\",\"sourceRevision\":\"1\",\"documents\":[null]}";
        MvcResult documents = mvc.perform(post("/api/v1/external-intakes").contentType(MediaType.APPLICATION_JSON)
                .content(nullDocument)).andReturn();
        assertEquals(400, documents.getResponse().getStatus());
        assertEquals("documents must not contain null entries", message(documents));
        verifyNoInteractions(intake);
    }

    @Test
    void aNullValueOnNewCaseIs400WithAClearMessage() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/packages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageType\":\"MANUAL_UPLOAD\",\"metadata\":{\"note\":null}}")).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("VALIDATION_ERROR", errorCode(result));
        assertEquals("metadata must not contain null values", message(result));
    }

    @Test
    void theApplicationInputRefusesNullsWithAValidationErrorToo() {
        var error = assertThrows(com.securedoc.platform.application.exception.ValidationException.class,
                () -> new com.securedoc.platform.application.intake.external.PushIntakeCreateInput("S-1", "1",
                        java.util.Collections.singletonMap("k", null), null, null));
        assertEquals("businessIdentifiers must not contain null values", error.getMessage());
    }
}
