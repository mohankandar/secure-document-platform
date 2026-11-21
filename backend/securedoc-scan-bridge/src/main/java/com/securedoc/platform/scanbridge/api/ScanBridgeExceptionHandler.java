package com.securedoc.platform.scanbridge.api;

import com.securedoc.platform.scanbridge.model.ApiErrorResponse;
import com.securedoc.platform.scanbridge.service.ScanBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Task 0025: every body is {@code errorCode} (as before), {@code code} (the LV code) and {@code message} (that code's
 * title). The exception's own text is not sent.
 */
@RestControllerAdvice
public class ScanBridgeExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ScanBridgeExceptionHandler.class);

    @ExceptionHandler(ScanBridgeException.class)
    public ResponseEntity<ApiErrorResponse> handleScanBridge(ScanBridgeException ex) {
        return ResponseEntity.status(ex.status()).body(ApiErrorResponse.of(ex.errorCode()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleDurabilityFailure(IllegalStateException ex) {
        ApiErrorResponse body = ApiErrorResponse.of("BRIDGE_LOCAL_STATE_FAILURE");
        log.warn("SCAN_BRIDGE_LOCAL_STATE_FAILURE code={} exceptionType={}", body.code(), ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        ApiErrorResponse body = ApiErrorResponse.of("BRIDGE_INTERNAL_ERROR");
        log.warn("SCAN_BRIDGE_REQUEST_FAILED code={} exceptionType={}", body.code(), ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
