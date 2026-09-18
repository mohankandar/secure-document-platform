package com.securedoc.platform.scanbridge.service;

import org.springframework.http.HttpStatus;

public class ScanBridgeException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;

    public ScanBridgeException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String errorCode() { return errorCode; }
    public HttpStatus status() { return status; }
}
