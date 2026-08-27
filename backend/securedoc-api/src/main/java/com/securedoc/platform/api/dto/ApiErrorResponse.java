package com.securedoc.platform.api.dto;

import java.time.Instant;

public record ApiErrorResponse(
        String errorCode,
        String message,
        String correlationId,
        Instant timestamp
) {
    public static ApiErrorResponse of(String errorCode, String message, String correlationId) {
        return new ApiErrorResponse(errorCode, message, correlationId, Instant.now());
    }
}
