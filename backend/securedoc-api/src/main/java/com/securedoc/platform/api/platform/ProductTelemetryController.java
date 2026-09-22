package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Platform Operations visibility into the privacy-safe product-telemetry contract. */
@RestController
@RequestMapping("/api/v1/platform/product-telemetry")
public class ProductTelemetryController {
    private final ProductTelemetryService telemetryService;

    public ProductTelemetryController(ProductTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping("/status")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<Status> status() {
        return ResponseEntity.ok(new Status(
                telemetryService.mode().name(),
                telemetryService.enabled(),
                telemetryService.installationIdConfigured(),
                "ALLOW_LIST_ONLY",
                List.of(
                        "package.processing.attempt",
                        "package.processing.failure",
                        "package.processing.finished",
                        "package.processing.recovery_exhausted",
                        "package.retention.purged",
                        "package.technical_attention.action",
                        "package.technical_attention.expired"),
                List.of(
                        "document/PDF/image content",
                        "OCR or extracted text/values",
                        "business identifiers, names, addresses or account values",
                        "raw prompts/model responses",
                        "customer-controlled filenames or filesystem paths",
                        "actor identities")));
    }

    public record Status(String mode,
                         boolean enabled,
                         boolean installationIdConfigured,
                         String privacyContract,
                         List<String> eventAllowList,
                         List<String> prohibitedData) {}
}
