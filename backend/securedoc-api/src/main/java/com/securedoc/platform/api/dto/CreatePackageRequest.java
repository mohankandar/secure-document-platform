package com.securedoc.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreatePackageRequest(
        @NotBlank(message = "packageType is required") String packageType,
        Map<String,String> businessIdentifiers,
        Map<String,String> metadata
) {
    public CreatePackageRequest {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
