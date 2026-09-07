package com.securedoc.platform.api.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesValidIncomingCorrelationIdForWholeRequest() throws Exception {
        UUID expected = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER_CORRELATION_ID, expected.toString());
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) -> {
            observed.set(MDC.get("correlationId"));
            assertEquals(expected, req.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID));
        });

        assertEquals(expected.toString(), observed.get());
        assertEquals(expected.toString(), response.getHeader(RequestCorrelationFilter.HEADER_CORRELATION_ID));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void replacesInvalidIncomingCorrelationId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER_CORRELATION_ID, "not-a-uuid");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            Object attribute = req.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
            assertInstanceOf(UUID.class, attribute);
            assertDoesNotThrow(() -> UUID.fromString(MDC.get("correlationId")));
        });

        assertDoesNotThrow(() -> UUID.fromString(response.getHeader(RequestCorrelationFilter.HEADER_CORRELATION_ID)));
    }
}
