package com.securedoc.platform.api.security;

import java.util.Locale;
import java.util.Optional;

/** Canonical product roles plus narrow identity-boundary aliases retained only for migration. */
public enum ProductRole {
    DOCUMENT_PROCESSOR,
    DOCUMENT_SUPERVISOR,
    BUSINESS_ADMIN,
    REPORT_VIEWER,
    TENANT_IT_ADMIN,
    PLATFORM_OPERATIONS_ADMIN;

    public static Optional<ProductRole> fromIdentityName(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USER", "REVIEWER" -> Optional.of(DOCUMENT_PROCESSOR);
            case "APPROVER" -> Optional.of(DOCUMENT_SUPERVISOR);
            default -> {
                try {
                    yield Optional.of(ProductRole.valueOf(normalized));
                } catch (IllegalArgumentException ignored) {
                    yield Optional.empty();
                }
            }
        };
    }
}
