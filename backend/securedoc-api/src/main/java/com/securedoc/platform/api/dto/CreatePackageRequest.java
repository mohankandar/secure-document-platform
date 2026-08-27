package com.securedoc.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreatePackageRequest(
        @NotBlank(message = "packageType is required")
        String packageType,
        Map<String, String> metadata
) {
    public CreatePackageRequest {
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
