package com.securedoc.platform.api.dto;

import com.securedoc.platform.application.exception.ValidationException;

import java.util.Collection;
import java.util.Map;

/**
 * Task 0023 D1 (#32): request bodies whose maps or lists hold a JSON {@code null} are refused with a 400 that names
 * the field (LexaVault's own name), never a key or value the client sent. Thrown from a record constructor while the
 * body is read; {@code GlobalExceptionHandler} answers it as the {@link ValidationException} it is.
 */
public final class RequestValues {

    private RequestValues() {
    }

    public static void requireNoNullValues(Map<String, String> values, String field) {
        if (values != null && values.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new ValidationException(field + " must not contain null values");
        }
    }

    public static void requireNoNullEntries(Collection<?> values, String field) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ValidationException(field + " must not contain null entries");
        }
    }
}
