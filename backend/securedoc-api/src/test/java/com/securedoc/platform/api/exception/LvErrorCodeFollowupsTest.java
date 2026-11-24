package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.config.StartupFailureLogger;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.api.testsupport.LogCapture;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.TenantRoutingSensitiveContentAnalysisPort;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.pdf.PdfUnreadableException;
import com.securedoc.platform.infrastructure.review.PdfBoxReviewPageRenderAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 0026: the 8 new codes, the corrected and wired codes, and the API {@code detail} field (present, names the
 * field, never the value sent).
 */
class LvErrorCodeFollowupsTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    private static ApiErrorResponse body(ResponseEntity<ApiErrorResponse> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    // ---------- section 1: the new codes, through the API ----------

    @Test
    void anItemThatIsNotACaseIsSys008AndACaseStaysInt025() {
        ApiErrorResponse item = body(handler.handleNotFound(new NotFoundException("RoutingRule", "id-1"),
                request("/api/v1/routing-rules/id-1")));
        assertEquals("LV-SYS-008", item.code());
        assertEquals("Item not found", item.message());
        assertEquals("NOT_FOUND", item.errorCode());
        assertFalse(item.message().contains("id-1"));
        ApiErrorResponse kase = body(handler.handleNotFound(new NotFoundException("DocumentPackage", "id-2"),
                request("/api/v1/packages/id-2")));
        assertEquals("LV-INT-025", kase.code());
    }

    @Test
    void supportActionsInTheWrongStateAreCfg011() {
        ResponseEntity<ApiErrorResponse> response = handler.handleValidation(new ValidationException(LvErrorCode.CFG_011,
                "This support incident is historical/closed; active package actions are unavailable"),
                request("/api/v1/support/incidents/x/actions"));
        // Task 0027 (owner, 2026-11-21): 409, as the code's table says (it was 400 in task 0026).
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("LV-CFG-011", body(response).code());
        assertEquals("Support action not possible now", body(response).message());
    }

    @Test
    void aReviewPageOutOfRangeIsRev009() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(200, 200)));
            document.save(output);
            pdf = output.toByteArray();
        }
        PdfBoxReviewPageRenderAdapter adapter = new PdfBoxReviewPageRenderAdapter();
        for (int page : new int[] {0, 2, 99}) {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> adapter.render(new ByteArrayInputStream(pdf), page));
            assertEquals(LvErrorCode.REV_009, LvErrorCodes.of(ex), "page " + page);
        }
    }

    // ---------- section 3: wired codes ----------

    @Test
    void aPdfThatCanNotBeOpenedDuringProcessingIsPrc001() {
        byte[] notAPdf = "%PDF-1.7 this is cut short".getBytes(StandardCharsets.US_ASCII);
        PdfBoxTextExtractionAdapter nativeAdapter = new PdfBoxTextExtractionAdapter();
        PdfUnreadableException nativeFailure = assertThrows(PdfUnreadableException.class,
                () -> nativeAdapter.extract(new ByteArrayInputStream(notAPdf), "application/pdf"));
        assertEquals(LvErrorCode.PRC_001, LvErrorCodes.of(nativeFailure));
        assertEquals(LvErrorCode.PRC_001, com.securedoc.platform.application.resilience.TechnicalFailureClassifier
                .classify(new RuntimeException("Text extraction failed", nativeFailure)).lvCode());
        assertFalse(nativeFailure.getMessage().contains("cut short"));

        HybridPdfTextExtractionAdapter hybrid = new HybridPdfTextExtractionAdapter(nativeAdapter,
                input -> { throw new AssertionError("no OCR for a PDF that can't be opened"); },
                new TesseractOcrProperties());
        PdfUnreadableException hybridFailure = assertThrows(PdfUnreadableException.class,
                () -> hybrid.extract(new ByteArrayInputStream(notAPdf), "application/pdf"));
        assertEquals(LvErrorCode.PRC_001, LvErrorCodes.of(hybridFailure));
    }

    @Test
    void noAiSettingsForTheEntityIsAi006() {
        TenantRoutingSensitiveContentAnalysisPort port = new TenantRoutingSensitiveContentAnalysisPort(
                mock(SensitiveContentAnalysisPort.class), Map.of(), true);
        SensitiveContentAnalysisPort.AnalysisRequest analysis = mock(SensitiveContentAnalysisPort.AnalysisRequest.class);
        when(analysis.tenantId()).thenReturn(TenantId.generate());
        TechnicalDependencyException ex = assertThrows(TechnicalDependencyException.class, () -> port.analyze(analysis));
        assertEquals(LvErrorCode.AI_006, LvErrorCodes.of(ex));
    }

    @Test
    void databaseAndQueueFullFailuresReachingTheApiCarryTheirCodes() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            ApiErrorResponse queue = body(handler.handleGeneric(new RejectedExecutionException("queue of host-1 full"),
                    request("/api/v1/packages/x/submit")));
            assertEquals("LV-SYS-004", queue.code());
            assertEquals("Too much work queued", queue.message());
            ApiErrorResponse database = body(handler.handleGeneric(
                    new RuntimeException("wrapped", new SQLException("password for user lexa")),
                    request("/api/v1/packages")));
            assertEquals("LV-SYS-003", database.code());
            String logged = logs.text();
            assertTrue(logged.contains("LV-SYS-004"), logged);
            assertFalse(logged.contains("password"), logged);
            assertFalse(logged.contains("host-1"), logged);
        }
    }

    @Test
    void startUpStopsLogTheirCodeAndTypeOnly() {
        assertEquals(LvErrorCode.SYS_005, codeAtStart(new IllegalStateException(
                "OIDC mode requires securedoc.security.oidc.issuer-uri and audience")));
        assertEquals(LvErrorCode.SYS_003, codeAtStart(new IllegalStateException("context",
                new SQLException("Connection to db-host:5432 refused"))));
        try (LogCapture logs = LogCapture.of(StartupFailureLogger.class)) {
            new StartupFailureLogger().onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(),
                    new String[0], null, new IllegalArgumentException("Invalid tenant UUID in profiles: secret-key")));
            String logged = logs.text();
            assertTrue(logged.contains("LV_STARTUP_FAILED code=LV-SYS-005 type=IllegalArgumentException"), logged);
            assertFalse(logged.contains("secret-key"), logged);
        }
    }

    private static LvErrorCode codeAtStart(Throwable failure) {
        try (LogCapture logs = LogCapture.of(StartupFailureLogger.class)) {
            new StartupFailureLogger().onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(),
                    new String[0], null, failure));
            String logged = logs.text();
            for (LvErrorCode code : LvErrorCode.values()) {
                if (logged.contains("code=" + code.code() + " ")) return code;
            }
            return fail("no code logged: " + logged);
        }
    }

    // ---------- section 4: the API detail field ----------

    @Test
    void anIntakeCaseFieldRefusalNamesTheFieldNeverTheValueSent() {
        TenantId tenant = TenantId.generate();
        BusinessIdentifierDefinition loan = BusinessIdentifierDefinition.create(tenant, "loan_number", "Loan number",
                true, true, true, false, true, 1);
        BusinessIdentifierDefinitionRepository definitions = mock(BusinessIdentifierDefinitionRepository.class);
        when(definitions.findActiveByTenantId(tenant)).thenReturn(List.of(loan));
        BusinessIdentityService identities = new BusinessIdentityService(definitions, mock(BusinessRecordRepository.class));

        IntakeValidationException unknown = assertThrows(IntakeValidationException.class,
                () -> identities.resolveOrCreate(tenant, Map.of("loan_number", "LN-1", "jane_doe_ssn", "123-45-6789")));
        ApiErrorResponse unknownBody = body(handler.handleValidation(unknown, request("/api/v1/external-intakes")));
        assertEquals("LV-INT-014", unknownBody.code());
        assertNotNull(unknownBody.detail());
        assertFalse(unknownBody.detail().contains("jane_doe_ssn"), unknownBody.detail());
        assertFalse(unknownBody.detail().contains("123-45-6789"), unknownBody.detail());

        IntakeValidationException missing = assertThrows(IntakeValidationException.class,
                () -> identities.resolveOrCreate(tenant, Map.of()));
        ApiErrorResponse missingBody = body(handler.handleValidation(missing, request("/api/v1/external-intakes")));
        assertEquals("LV-INT-013", missingBody.code());
        assertEquals("Case field Loan number is required", missingBody.detail());
    }

    @Test
    void aSettingsRefusalHasItsFieldAndRangeAsTheDetail() {
        ApiErrorResponse response = body(handler.handleValidation(new ValidationException(LvErrorCode.CFG_001,
                "Automatic redaction threshold must be between 0.50 and 0.99"), request("/api/v1/admin/policy")));
        assertEquals("LV-CFG-001", response.code());
        assertEquals("Automatic redaction threshold must be between 0.50 and 0.99", response.detail());
    }

    @Test
    void theThrowersOwnDetailComesFirstAndOtherAreasHaveNone() {
        ApiErrorResponse own = body(handler.handleValidation(new ValidationException(LvErrorCode.INT_003,
                "documents[0] is blank", "Field externalSourceDocumentId is required"), request("/api/v1/external-intakes")));
        assertEquals("Field externalSourceDocumentId is required", own.detail());
        ApiErrorResponse review = body(handler.handleValidation(new ValidationException(LvErrorCode.REV_003,
                "Finding abc is still open"), request("/api/v1/review")));
        assertNull(review.detail(), "a review refusal is not an intake or settings refusal");
    }

    @Test
    void detailIsLeftOutOfTheJsonWhenThereIsNone() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build();
        String without = mapper.writeValueAsString(ApiErrorResponse.of("X", LvErrorCode.SYS_001, "c"));
        assertFalse(without.contains("\"detail\""), without);
        String with = mapper.writeValueAsString(ApiErrorResponse.of("X", LvErrorCode.INT_002, "c")
                .withDetail("Header X-Content-SHA256 is missing"));
        assertTrue(with.contains("\"detail\":\"Header X-Content-SHA256 is missing\""), with);
    }
}
