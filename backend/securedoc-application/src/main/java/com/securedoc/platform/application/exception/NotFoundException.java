package com.securedoc.platform.application.exception;

import java.util.Set;

/**
 * Thrown when a requested resource does not exist in the caller's tenant.
 *
 * <p>Task 0025: a case (package or intake) not found is {@code LV-INT-025} "Case not found".
 */
public class NotFoundException extends ApplicationException {

    /** The resource types that are a case for the person or source system. */
    private static final Set<String> CASE_TYPES = Set.of("DocumentPackage", "ExternalIntake", "Package");

    private final String resourceType;

    public NotFoundException(String resourceType, String resourceId) {
        super("NOT_FOUND", resourceType + " not found: " + resourceId);
        this.resourceType = resourceType;
    }

    public String resourceType() {
        return resourceType;
    }

    /** True when the missing resource is a case. */
    public boolean caseNotFound() {
        return resourceType != null && CASE_TYPES.contains(resourceType);
    }
}
