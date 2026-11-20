package com.securedoc.platform.api.compliance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.ComplianceReportController;
import com.securedoc.platform.api.controller.ComplianceReportController.CreateComplianceReportRequest;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0017 at the API boundary: who may create which edition, the response headers, the entity, and that no
 * borrower name, file name, loan number or business identifier value reaches the PDF, its audit record or the logs.
 */
class ComplianceReportApiTest {

    private static final Clock NOV_19 = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant IN_PERIOD = Instant.parse("2026-08-12T15:00:00Z");
    private static final String SYSTEM = RequestActorDisplayName.SYSTEM_ACTOR_ID.toString();

    /** Markers a borrower's file name, loan number and business identifier value would carry. */
    private static final List<String> MARKERS = List.of("MARKER-FILE-Q7Z", "LOAN-MARKER-55120", "BIZID-MARKER-90817",
            "Borrower Zelda Marker");

    private final Tenant entityA = Tenant.create("Residential lending", "residential-lending");
    private final Tenant entityB = Tenant.create("Commercial lending", "commercial-lending");
    private final List<AuditRecord> source = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> audits = new CopyOnWriteArrayList<>();
    private final RequestActorDisplayName names = new RequestActorDisplayName();
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level rootLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevel = root.getLevel();
        root.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logs);
        root.setLevel(rootLevel);
        names.clear();
    }

    private ComplianceReportController controller(AuthenticatedPrincipal principal) {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(principal);
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        return new ComplianceReportController(
                InMemoryComplianceReads.service(source, audits, List.of(entityA, entityB), principal.tenantId(), names, NOV_19),
                resolver, new ProductAuthorizationPolicy());
    }

    private AuthenticatedPrincipal as(ProductRole role, Tenant tenant) {
        return AuthenticatedPrincipal.of(ActorId.generate(), tenant.id(), role.name().toLowerCase() + "@example.test",
                Set.of(role.name()));
    }

    private static CreateComplianceReportRequest body(boolean full) {
        return new CreateComplianceReportRequest("LAST_QUARTER", null, null, "America/New_York", full);
    }

    @Test
    void eachRoleGetsOnlyItsEditionAndEveryRefusalIsAuditedAs403() {
        for (ProductRole role : ProductRole.values()) {
            for (boolean full : new boolean[]{false, true}) {
                // U022 step 0: the full permission also allows the summary; the summary permission only the summary.
                boolean allowed = role == ProductRole.TENANT_IT_ADMIN || (!full && role == ProductRole.BUSINESS_ADMIN);
                AuthenticatedPrincipal who = as(role, entityA);
                int before = audits.size();
                if (allowed) {
                    ResponseEntity<byte[]> response = controller(who).create(body(full), mock(HttpServletRequest.class));
                    assertEquals(200, response.getStatusCode().value(), role + " full=" + full);
                    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
                    assertEquals(AuditOutcome.SUCCESS, audits.get(audits.size() - 1).outcome());
                } else {
                    assertThrows(AccessDeniedException.class,
                            () -> controller(who).create(body(full), mock(HttpServletRequest.class)), role + " full=" + full);
                    assertEquals(before + 1, audits.size(), "the refusal is recorded");
                    AuditRecord refused = audits.get(audits.size() - 1);
                    assertEquals(AuditAction.COMPLIANCE_REPORT_CREATED, refused.action());
                    assertEquals(AuditOutcome.DENIED, refused.outcome());
                    assertEquals("edition=" + (full ? "FULL" : "SUMMARY") + ", refused=ROLE", refused.detail());
                }
            }
        }
    }

    @Test
    void theItAdministratorMayUntickTheFullAuditTrailAndStillSignsAsItAdministrator() throws Exception {
        AuthenticatedPrincipal it = as(ProductRole.TENANT_IT_ADMIN, entityA);
        names.bind(it.actorId(), "Robin IT", "robin");
        byte[] pdf = controller(it).create(body(false), mock(HttpServletRequest.class)).getBody();
        AuditRecord created = audits.get(audits.size() - 1);
        assertEquals(AuditOutcome.SUCCESS, created.outcome());
        assertTrue(created.detail().contains("edition=SUMMARY"), created.detail());
        String all = text(pdf, 1, Integer.MAX_VALUE);
        assertTrue(all.contains("Robin IT, IT Administrator"));
        assertFalse(all.contains("Appendix"), "the summary edition has no appendix");
    }

    @Test
    void aRefusalTheEndpointRecordsItselfIsMarkedSoItIsNeverRecordedTwice() {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/compliance-reports");
        assertThrows(AccessDeniedException.class,
                () -> controller(as(ProductRole.BUSINESS_ADMIN, entityA)).create(body(true), request));
        assertTrue(com.securedoc.platform.api.security.AccessDeniedAuditRecorder.isRecorded(request));
    }

    @Test
    void capabilitiesPerRole() {
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        for (ProductRole role : ProductRole.values()) {
            Set<UserCapability> caps = policy.capabilitiesFor(Set.of(role.name()));
            assertEquals(role == ProductRole.BUSINESS_ADMIN, caps.contains(UserCapability.COMPLIANCE_REPORT_SUMMARY), role.name());
            assertEquals(role == ProductRole.TENANT_IT_ADMIN, caps.contains(UserCapability.COMPLIANCE_REPORT_FULL), role.name());
        }
        assertFalse(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.COMPLIANCE_REPORT_FULL));
    }

    @Test
    void theHeaderMatchesTheBytesAndTheAuditRecordHoldsBothHashes() throws Exception {
        AuthenticatedPrincipal it = as(ProductRole.TENANT_IT_ADMIN, entityA);
        names.bind(it.actorId(), "Robin IT", "robin");
        ResponseEntity<byte[]> response = controller(it).create(body(true), mock(HttpServletRequest.class));
        byte[] pdf = response.getBody();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        assertEquals(sha, response.getHeaders().getFirst(ComplianceReportController.FILE_SHA256_HEADER));
        assertEquals("attachment; filename=\"compliance-report-residential-lending-2026-07-01-2026-09-30.pdf\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        AuditRecord created = audits.get(audits.size() - 1);
        assertTrue(created.detail().endsWith(", fileSha256=" + sha), created.detail());
        String fingerprint = created.detail().replaceAll(".*fingerprint=([0-9a-f]{64}).*", "$1");
        String cover = text(pdf, 1, 1);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < 64; i += 4) grouped.append(i == 0 ? "" : " ").append(fingerprint, i, i + 4);
        assertTrue(cover.contains(grouped), "the cover prints the recorded content fingerprint");
        assertTrue(cover.contains("Robin IT, IT Administrator"));
        assertEquals("Robin IT", created.actorDisplayName());
    }

    @Test
    void theEntityIsTheSignedInOneOnly() throws Exception {
        source.add(record(entityB, AuditAction.SENSITIVE_DATA_POLICY_CHANGED, SYSTEM, "Commercial only",
                "Sensitive-data policy changed: enabledCount=1, threshold=0.90, reviewMode=REVIEW_ALL, maskSsnLastFour=true, "
                        + "enabledTypes=SSN, preset=STRICT, previous=none"));
        byte[] pdf = controller(as(ProductRole.TENANT_IT_ADMIN, entityA)).create(body(true),
                mock(HttpServletRequest.class)).getBody();
        String all = text(pdf, 1, Integer.MAX_VALUE);
        assertTrue(all.contains("Residential lending only. Other entities are never included."));
        assertFalse(all.contains("Commercial lending"));
        assertFalse(all.contains("Commercial only"));
        assertFalse(all.contains("Policy saved for the first time"), "another entity's change is not read");
    }

    @Test
    void markersInFileNamesLoanNumbersAndIdentifierValuesNeverReachThePdfTheAuditOrTheLogs() throws Exception {
        String person = UUID.randomUUID().toString();
        // What the product records about such a case, with markers where those values would appear if leaked.
        source.add(record(entityA, AuditAction.DOCUMENT_EXPORTED, SYSTEM, null,
                "Document exported: destination=LOCAL_FOLDER, filename=MARKER-FILE-Q7Z.pdf"));
        source.add(record(entityA, AuditAction.POLLING_RUN_FAILED, SYSTEM, null,
                "Polling failed: detail=LOAN-MARKER-55120 not found for Borrower Zelda Marker"));
        source.add(record(entityA, AuditAction.INTEGRATION_CONNECTION_UPDATED, person, "Robin IT",
                "Integration connection updated: name=BIZID-MARKER-90817"));
        source.add(record(entityA, AuditAction.DERIVED_DOCUMENT_DELIVERED, SYSTEM, null,
                "Derived document delivered: destination=LOAN-MARKER-55120"));
        source.add(record(entityA, AuditAction.FINDING_CONFIRMED, person, "Robin IT", "Finding confirmed: type=SSN"));
        for (boolean full : new boolean[]{false, true}) {
            ProductRole role = full ? ProductRole.TENANT_IT_ADMIN : ProductRole.BUSINESS_ADMIN;
            byte[] pdf = controller(as(role, entityA)).create(body(full), mock(HttpServletRequest.class)).getBody();
            String all = text(pdf, 1, Integer.MAX_VALUE);
            for (String marker : MARKERS) {
                assertFalse(all.contains(marker), "PDF (full=" + full + ") holds " + marker);
                assertFalse(all.replaceAll("\\s+", "").contains(marker.replaceAll("\\s+", "")),
                        "PDF (full=" + full + ") holds " + marker + " across a line break");
            }
            if (full) {
                assertTrue(all.contains("Exported a document"), "the record is listed, its detail is not");
                assertTrue(all.contains("Redacted a Social Security Number"));
            }
        }
        for (AuditRecord a : audits) {
            for (String marker : MARKERS) assertFalse(String.valueOf(a.detail()).contains(marker), a.detail());
        }
        for (ILoggingEvent event : logs.list) {
            for (String marker : MARKERS) assertFalse(event.getFormattedMessage().contains(marker), event.getFormattedMessage());
        }
    }

    private static AuditRecord record(Tenant tenant, AuditAction action, String actor, String displayName, String detail) {
        return new AuditRecord(UUID.randomUUID(), tenant.id(), ActorId.of(UUID.fromString(actor)), action, "Test",
                UUID.randomUUID().toString(), AuditOutcome.SUCCESS, CorrelationId.generate(), detail, IN_PERIOD,
                displayName, UUID.randomUUID().toString());
    }

    static String text(byte[] pdf, int from, int to) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(from);
            stripper.setEndPage(Math.min(to, doc.getNumberOfPages()));
            return stripper.getText(doc).replaceAll("[ \\t]*\\R[ \\t]*", " ");
        }
    }
}
