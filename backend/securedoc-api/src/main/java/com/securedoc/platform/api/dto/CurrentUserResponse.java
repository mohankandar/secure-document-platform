package com.securedoc.platform.api.dto;

import java.util.Set;

public record CurrentUserResponse(
        String username,
        Set<String> roles,
        String tenantName,
        String tenantSlug,
        String authenticationMode,
        Set<String> capabilities
) {}
