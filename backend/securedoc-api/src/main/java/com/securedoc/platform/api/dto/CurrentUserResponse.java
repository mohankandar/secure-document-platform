package com.securedoc.platform.api.dto;

import java.util.Set;

public record CurrentUserResponse(
        String username,
        Set<String> roles,
        String tenantName,
        String tenantSlug,
        String authenticationMode,
        Set<String> capabilities,
        /** Task 0058: the entity's time zone (its own, else the customer's); null when none is set. */
        String timeZone
) {}
