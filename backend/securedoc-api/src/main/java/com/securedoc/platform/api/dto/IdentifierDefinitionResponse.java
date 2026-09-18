package com.securedoc.platform.api.dto;

import java.util.Set;

public record IdentifierDefinitionResponse(
        String key,
        String label,
        boolean required,
        boolean primary,
        boolean searchable,
        boolean extractable,
        boolean displayable,
        int displayOrder,
        String validationType,
        Integer exactLength,
        Integer minLength,
        Integer maxLength,
        String caseNormalization,
        boolean stripSeparators,
        Set<String> allowedValues,
        String customPattern
) {}
