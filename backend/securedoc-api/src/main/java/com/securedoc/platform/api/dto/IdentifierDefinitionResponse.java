package com.securedoc.platform.api.dto;

public record IdentifierDefinitionResponse(
        String key,
        String label,
        boolean required,
        boolean primary,
        boolean searchable,
        boolean extractable,
        boolean displayable,
        int displayOrder
) {}
