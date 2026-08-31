package com.securedoc.platform.api.dto;

public record BusinessIdentifierResponse(
        String key,
        String label,
        String value,
        boolean primary,
        boolean searchable,
        boolean displayable,
        int displayOrder
) {}
