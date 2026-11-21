package com.securedoc.platform.scanbridge.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The Scan Bridge's error body. Task 0025: {@code errorCode} stays as before (the detail), {@code code} is the LV code
 * and {@code message} is that code's title, never text that could carry content.
 */
@JsonPropertyOrder({"errorCode", "code", "message"})
public record ApiErrorResponse(String errorCode, String code, String message) {

    /** The body for a bridge {@code errorCode}, with its LV code and title from {@link BridgeLvCodes}. */
    public static ApiErrorResponse of(String errorCode) {
        BridgeLvCode lv = BridgeLvCodes.forBridgeCode(errorCode);
        return new ApiErrorResponse(errorCode, lv.code(), lv.title());
    }
}
