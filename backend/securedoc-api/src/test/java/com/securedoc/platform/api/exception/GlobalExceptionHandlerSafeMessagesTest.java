package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityContextRefusalRecorder;
import com.securedoc.platform.api.testsupport.LogCapture;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.PersonFacingStateException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 B5, C5, C7 (#291, #167-#172, #239, #290, #293, #331): what the handler sends and logs. */
class GlobalExceptionHandlerSafeMessagesTest {
    private final List<AuditRecord> audits = new ArrayList<>();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null,
            new SecurityContextRefusalRecorder(new AuditService(audits::add)));

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/review-tasks/123/start");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/review-tasks/{taskId}/start");
        return request;
    }

    @Test
    void domainStateRulesGetTheFixedMessageAndTheRawTextIsNotLogged() {
        ReviewTask task = ReviewTask.create(TenantId.generate(), PackageId.generate());
        List<IllegalStateException> refusals = new ArrayList<>();
        // #170 start a task that is not ASSIGNED; #172 approve a task that is not IN_PROGRESS.
        refusals.add(assertThrows(IllegalStateException.class, () -> task.start(ActorId.generate())));
        refusals.add(assertThrows(IllegalStateException.class, () -> task.approve(ActorId.generate(), "ok")));
        for (IllegalStateException refusal : refusals) {
            try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
                var response = handler.handleIllegalState(refusal, request());
                assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
                assertEquals("INVALID_STATE", response.getBody().errorCode());
                assertEquals("This action is not possible in the current state.", response.getBody().message());
                assertFalse(response.getBody().message().contains("PENDING"));
                String logged = logs.text();
                assertTrue(logged.contains("INVALID_STATE_REJECTED code=INVALID_STATE type=IllegalStateException"));
                assertTrue(logged.contains("origin=ReviewTask."), logged);
                assertTrue(logged.contains("route=/api/v1/review-tasks/{taskId}/start"));
                assertFalse(logged.contains(refusal.getMessage()), "the raw text is never logged");
            }
        }
    }

    @Test
    void aLibraryOrDataLayerStateTextNeverReachesTheResponse() {
        var response = handler.handleIllegalState(
                new IllegalStateException("duplicate key value (borrower_name)=(Jane Doe)"), request());
        assertEquals("This action is not possible in the current state.", response.getBody().message());
    }

    @Test
    void aPersonFacingStateMessageWrittenByLexaVaultIsSent() {
        var response = handler.handlePersonFacingState(new PersonFacingStateException(
                "Activation requires a successful connection test from the last 15 minutes. Test the current draft again."),
                request());
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("INVALID_STATE", response.getBody().errorCode());
        assertTrue(response.getBody().message().startsWith("Activation requires"));
    }

    @Test
    void anIllegalArgumentIsLoggedByTypeAndOriginNeverByMessage() {
        IllegalArgumentException parse = assertThrows(IllegalArgumentException.class,
                () -> UUID.fromString("Jane-Doe-not-a-uuid"));
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            var response = handler.handleIllegalArgument(parse, request());
            assertEquals("Invalid request parameter", response.getBody().message());
            assertTrue(logs.text().contains("INVALID_ARGUMENT_REJECTED code=INVALID_ARGUMENT type=" + parse.getClass().getSimpleName()));
            assertFalse(logs.text().contains("Jane"));
        }
    }

    @Test
    void aTypeMismatchNamesTheParameterNotTheValue() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerSafeMessagesTest.class.getDeclaredMethod("endpoint", UUID.class), 0);
        var mismatch = new MethodArgumentTypeMismatchException("Jane Doe", UUID.class, "providerId", parameter, null);
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            var response = handler.handleTypeMismatch(mismatch, request());
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("Invalid value for request parameter 'providerId'", response.getBody().message());
            assertFalse(logs.text().contains("Jane"));
        }
    }

    @SuppressWarnings("unused")
    private void endpoint(UUID providerId) { }

    @Test
    void anUnhandledExceptionLogsWhereItHappenedAndTheCorrelationButNotItsMessage() {
        RuntimeException unexpected = new NullPointerException("borrower Jane Doe");
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            var response = handler.handleGeneric(unexpected, request());
            assertEquals("An unexpected error occurred", response.getBody().message());
            String logged = logs.text();
            assertTrue(logged.contains("correlation=" + response.getBody().correlationId()), logged);
            assertTrue(logged.contains("reasonCode=NullPointerException"), logged);
            assertTrue(logged.contains("component=GlobalExceptionHandlerSafeMessagesTest."), logged);
            assertFalse(logged.contains("Jane"));
        }
    }

    @Test
    void anOtherApplicationExceptionLogsItsCode() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            handler.handleApplicationException(new ApplicationException("SOME_CODE", "text") { }, request());
            assertTrue(logs.text().contains("APPLICATION_EXCEPTION code=SOME_CODE"));
        }
    }

    @Test
    void anAttributedSignInRefusalIsAuditedWithItsReasonAndTheResponseStaysSafe() {
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        SecurityContextException refusal = new SecurityContextException(
                "Authenticated identity has no mapped application role", "NO_MAPPED_ROLE").attributedTo(tenant, actor);
        MockHttpServletRequest request = request();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/me");
        try (LogCapture logs = LogCapture.of(SecurityContextRefusalRecorder.class)) {
            var response = handler.handleSecurityContext(refusal, request);
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertEquals("SECURITY_CONTEXT_INVALID", response.getBody().errorCode());
            assertEquals("Authenticated identity is not authorized for this tenant/application",
                    response.getBody().message());
            assertTrue(logs.text().contains("SECURITY_CONTEXT_REFUSED reason=NO_MAPPED_ROLE method=POST route=/api/v1/me"));
            assertTrue(logs.text().contains("audited=true"));
        }
        assertEquals(1, audits.size());
        AuditRecord audit = audits.get(0);
        assertEquals(AuditAction.SECURITY_CONTEXT_INVALID, audit.action());
        assertEquals(AuditOutcome.DENIED, audit.outcome());
        assertEquals(tenant, audit.tenantId());
        assertEquals(actor, audit.actorId());
        assertEquals("method=POST, route=/api/v1/me, reason=NO_MAPPED_ROLE", audit.detail());
    }

    @Test
    void aRefusalBeforeAnyEntityIsKnownIsLoggedOnly() {
        SecurityContextException refusal = new SecurityContextException(
                "Invalid role in X-Actor-Roles header: Jane Doe", "INVALID_LOCAL_ROLE_HEADER");
        try (LogCapture logs = LogCapture.of(SecurityContextRefusalRecorder.class)) {
            handler.handleSecurityContext(refusal, request());
            assertTrue(logs.text().contains("reason=INVALID_LOCAL_ROLE_HEADER"));
            assertTrue(logs.text().contains("audited=false"));
            assertFalse(logs.text().contains("Jane"), "the refusal's message is never logged");
        }
        assertTrue(audits.isEmpty());
    }

    @Test
    void aFailingAuditStoreNeverTurnsTheRefusalIntoSomethingElse() {
        var broken = new GlobalExceptionHandler(null, new SecurityContextRefusalRecorder(new AuditService(record -> {
            throw new IllegalStateException("database down");
        })));
        var response = broken.handleSecurityContext(new SecurityContextException("x", "NO_MAPPED_ROLE")
                .attributedTo(TenantId.generate(), ActorId.generate()), request());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
