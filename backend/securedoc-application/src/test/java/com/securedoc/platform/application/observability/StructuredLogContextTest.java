package com.securedoc.platform.application.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLogContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void onlyAllowListedOperationalKeysEnterMdc() {
        try (var ignored = StructuredLogContext.open(Map.of(
                "incidentId", "incident-1",
                "reasonCode", "AI_INVALID_RESPONSE",
                "rawPrompt", "do not log me",
                "documentText", "do not log me either"))) {
            assertEquals("incident-1", MDC.get("incidentId"));
            assertEquals("AI_INVALID_RESPONSE", MDC.get("reasonCode"));
            assertNull(MDC.get("rawPrompt"));
            assertNull(MDC.get("documentText"));
        }
    }

    @Test
    void wrappedTaskReceivesSafeSnapshotAndRestoresWorkerContext() {
        MDC.put("correlationId", "request-correlation");
        Runnable wrapped = StructuredLogContext.wrap(() -> assertEquals(
                "request-correlation", MDC.get("correlationId")));

        MDC.clear();
        MDC.put("correlationId", "worker-correlation");
        wrapped.run();

        assertEquals("worker-correlation", MDC.get("correlationId"));
    }

    @Test
    void scopeRestoresPreviousValues() {
        MDC.put("status", "OLD");
        try (var ignored = StructuredLogContext.open(Map.of("status", "NEW", "eventName", "TEST_EVENT"))) {
            assertEquals("NEW", MDC.get("status"));
            assertEquals("TEST_EVENT", MDC.get("eventName"));
        }
        assertEquals("OLD", MDC.get("status"));
        assertNull(MDC.get("eventName"));
    }
}
