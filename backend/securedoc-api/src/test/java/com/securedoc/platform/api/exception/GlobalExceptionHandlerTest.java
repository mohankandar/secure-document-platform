package com.securedoc.platform.api.exception;

import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();


    @Test
    void accessDeniedMapsTo403() {
        var request = new MockHttpServletRequest("GET", "/api/v1/support/incidents");
        var response = handler.handleAccessDenied(
                new AccessDeniedException("Cross-tenant support search requires Platform Operations capability"), request);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACCESS_DENIED", response.getBody().errorCode());
    }

    @Test
    void supportIncidentNotFoundMapsTo404() {
        var request = new MockHttpServletRequest("GET", "/api/v1/support/incidents/missing");
        var response = handler.handleNotFound(new NotFoundException("Support incident", "missing"), request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().errorCode());
    }

    @Test
    void malformedJsonMapsToSafe400InsteadOf500() {
        var request = new MockHttpServletRequest("POST", "/api/v1/external-intakes");
        var response = handler.handleMalformedJson(new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])), request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_REQUEST_BODY", response.getBody().errorCode());
        assertFalse(response.getBody().message().contains("bad json"));
    }

    @Test
    void retryableTechnicalDependencyMapsTo503WithStableFailureCode() {
        var request = new MockHttpServletRequest("POST", "/api/v1/integrations/derived/test");
        var failure = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                TechnicalFailureCode.DELIVERY_IO, true, "Derived destination unavailable");
        var response = handler.handleTechnicalDependency(failure, request);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DELIVERY_IO", response.getBody().errorCode());
    }

    @Test
    void nonRetryableTechnicalDependencyMapsTo502() {
        var request = new MockHttpServletRequest("POST", "/api/v1/integrations/derived/test");
        var failure = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Destination configuration is invalid");
        var response = handler.handleTechnicalDependency(failure, request);
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("DELIVERY_DESTINATION_INVALID", response.getBody().errorCode());
    }
}
