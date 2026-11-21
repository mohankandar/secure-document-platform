package com.securedoc.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.time.Instant;

/**
 * The API error body. Task 0025: {@code code} is the LexaVault error code ({@code LV-…}) and {@code message} its
 * title (for {@code LV-CFG-001} followed by the field and allowed range). {@code errorCode} stays the internal
 * identifier (API version 1), with {@code correlationId} and {@code timestamp} as before.
 *
 * <p>Task 0026: {@code detail}, optional and left out when there is none, tells an integrator <em>what</em> is wrong
 * in an intake or settings refusal: the field, header or part and the allowed range. It is LexaVault's own text and
 * never echoes a value that was sent.
 */
public record ApiErrorResponse(
        String errorCode,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
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
        return new ApiErrorResponse(errorCode, code.code(), message, null, correlationId, Instant.now());
    }

    /** The same body with {@code detail} (blank gives none). LexaVault's own words only, never a value sent. */
    public ApiErrorResponse withDetail(String what) {
        String trimmed = what == null || what.isBlank() ? null : what.trim();
        return new ApiErrorResponse(errorCode, code, message, trimmed, correlationId, timestamp);
    }
}
