package com.securedoc.platform.scanbridge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.model.ApiErrorResponse;
import com.securedoc.platform.scanbridge.service.ScanBridgeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025: the bridge's error body is errorCode (as before), code (LV) and message (the code's title). */
class ScanBridgeExceptionHandlerTest {
    private final ScanBridgeExceptionHandler handler = new ScanBridgeExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aTypedRefusalKeepsItsStatusAndErrorCodeAndGivesTheLvCodeAndTitle() throws Exception {
        ResponseEntity<ApiErrorResponse> response = handler.handleScanBridge(new ScanBridgeException("SCANNER_BUSY",
                "A scan package is already waiting for operator action on this scanner.", HttpStatus.CONFLICT));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(new ApiErrorResponse("SCANNER_BUSY", "LV-SCN-006", "Scanner busy"), response.getBody());
    }

    @Test
    void theExceptionTextIsNotSent() {
        ResponseEntity<ApiErrorResponse> response = handler.handleScanBridge(new ScanBridgeException(
                "INVALID_BUSINESS_IDENTIFIER_INPUT", "Contract 12345 for Jane Doe", HttpStatus.BAD_REQUEST));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("LV-INT-015", response.getBody().code());
        assertEquals("Case field value not valid", response.getBody().message());
    }

    @Test
    void localStateFailureIsScn013With503() {
        ResponseEntity<ApiErrorResponse> response = handler.handleDurabilityFailure(new IllegalStateException("x"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(new ApiErrorResponse("BRIDGE_LOCAL_STATE_FAILURE", "LV-SCN-013", "Scan Bridge can't save its state"),
                response.getBody());
    }

    @Test
    void anUnexpectedFailureIsSys001With500() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(new RuntimeException("x"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(new ApiErrorResponse("BRIDGE_INTERNAL_ERROR", "LV-SYS-001", "Something went wrong"), response.getBody());
    }

    @Test
    void anUnknownErrorCodeStillGetsABodyWithSys001() {
        ResponseEntity<ApiErrorResponse> response = handler.handleScanBridge(
                new ScanBridgeException("NOT_YET_MAPPED", "x", HttpStatus.CONFLICT));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(new ApiErrorResponse("NOT_YET_MAPPED", "LV-SYS-001", "Something went wrong"), response.getBody());
    }

    @Test
    void theJsonFieldsAreErrorCodeCodeMessageInThatOrder() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(ApiErrorResponse.of("BRIDGE_DISABLED")));
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);

        assertEquals(List.of("errorCode", "code", "message"), names);
        assertEquals("BRIDGE_DISABLED", json.get("errorCode").asText());
        assertEquals("LV-SCN-007", json.get("code").asText());
        assertEquals("Scan Bridge switched off or in the wrong mode", json.get("message").asText());
    }
}
