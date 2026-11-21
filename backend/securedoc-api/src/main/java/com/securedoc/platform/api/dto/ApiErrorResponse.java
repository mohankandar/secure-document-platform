package com.securedoc.platform.api.dto;

import com.securedoc.platform.domain.error.LvErrorCode;

import java.time.Instant;

/**
 * The API error body. Task 0025: {@code code} is the LexaVault error code ({@code LV-…}) and {@code message} its
 * title (for {@code LV-CFG-001} followed by the field and allowed range). {@code errorCode} stays the internal
 * identifier (API version 1), with {@code correlationId} and {@code timestamp} as before.
 */
public record ApiErrorResponse(
        String errorCode,
        String code,
        String message,
        String correlationId,
        Instant timestamp
) {
    /** The body for {@code lvCode}: its title is the message. */
    public static ApiErrorResponse of(String errorCode, LvErrorCode lvCode, String correlationId) {
        return of(errorCode, lvCode, null, correlationId);
    }

    /**
     * The body for {@code lvCode}. {@code settingDetail} is used only for {@code LV-CFG-001} ("Setting not valid"),
     * where the message names the field and the allowed range; it must be LexaVault's own text, never content.
     */
    public static ApiErrorResponse of(String errorCode, LvErrorCode lvCode, String settingDetail, String correlationId) {
        LvErrorCode code = lvCode == null ? LvErrorCode.FALLBACK : lvCode;
        String message = code.title();
        if (code == LvErrorCode.CFG_001 && settingDetail != null && !settingDetail.isBlank()) {
            message = message + ": " + settingDetail.trim();
        }
        return new ApiErrorResponse(errorCode, code.code(), message, correlationId, Instant.now());
    }
}
