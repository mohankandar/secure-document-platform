package com.securedoc.platform.scanbridge.api;

import com.securedoc.platform.scanbridge.model.ApiErrorResponse;
import com.securedoc.platform.scanbridge.service.ScanBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ScanBridgeExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ScanBridgeExceptionHandler.class);

    @ExceptionHandler(ScanBridgeException.class)
    public ResponseEntity<ApiErrorResponse> handleScanBridge(ScanBridgeException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiErrorResponse(ex.errorCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleDurabilityFailure(IllegalStateException ex) {
        log.warn("SCAN_BRIDGE_LOCAL_STATE_FAILURE exceptionType={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("BRIDGE_LOCAL_STATE_FAILURE",
                        "Scanner Bridge could not safely persist or recover local state."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.warn("SCAN_BRIDGE_REQUEST_FAILED exceptionType={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("BRIDGE_INTERNAL_ERROR", "Scanner Bridge request failed safely."));
    }
}
