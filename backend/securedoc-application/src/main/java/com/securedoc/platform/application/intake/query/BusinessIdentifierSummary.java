package com.securedoc.platform.application.intake.query;

public record BusinessIdentifierSummary(
        String key,
        String label,
        String value,
        boolean primary,
        boolean searchable,
        boolean displayable,
        int displayOrder
) {}
