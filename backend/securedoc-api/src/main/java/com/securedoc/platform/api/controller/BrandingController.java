package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.branding.*;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** UI-only product/customer branding. Branding never changes technical tenant/licensing identity. */
@RestController
public class BrandingController {
    private final BrandingService brandingService;
    private final RequestContextResolver contextResolver;

    public BrandingController(BrandingService brandingService, RequestContextResolver contextResolver) {
        this.brandingService = brandingService;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/api/v1/branding/current")
    public ResponseEntity<EffectiveBrandingResponse> current(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(EffectiveBrandingResponse.from(brandingService.effective(principal.tenantId())));
    }

    @GetMapping("/api/v1/platform/branding")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<BrandingOverviewResponse> overview() {
        return ResponseEntity.ok(BrandingOverviewResponse.from(brandingService.overview()));
    }

    @GetMapping("/api/v1/platform/branding/tenants/{tenantId}")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<TenantBrandingDetailResponse> tenantBranding(@PathVariable UUID tenantId) {
        var configuration = brandingService.tenantOverride(TenantId.of(tenantId));
        return ResponseEntity.ok(new TenantBrandingDetailResponse(tenantId, configuration != null,
                BrandingConfigurationResponse.from(configuration)));
    }

    @PutMapping("/api/v1/platform/branding/deployment")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<BrandingConfigurationResponse> updateDeployment(@RequestBody BrandingUpdateRequest body,
                                                                         HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var saved = brandingService.updateDeployment(body.toUpdate(), principal.actorId(), principal.tenantId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(BrandingConfigurationResponse.from(saved));
    }

    @PutMapping("/api/v1/platform/branding/tenants/{tenantId}")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<BrandingConfigurationResponse> updateTenant(@PathVariable UUID tenantId,
                                                                      @RequestBody BrandingUpdateRequest body,
                                                                      HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var saved = brandingService.updateTenant(TenantId.of(tenantId), body.toUpdate(), principal.actorId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(BrandingConfigurationResponse.from(saved));
    }

    @DeleteMapping("/api/v1/platform/branding/tenants/{tenantId}")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<Void> clearTenant(@PathVariable UUID tenantId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        brandingService.clearTenant(TenantId.of(tenantId), principal.actorId(), contextResolver.resolveCorrelationId(request));
        return ResponseEntity.noContent().build();
    }

    public record BrandingAssetRequest(String dataUrl, boolean clear) {
        BrandingUpdate.BrandingAssetUpdate toUpdate() {
            if (clear) return new BrandingUpdate.BrandingAssetUpdate(null, null, true);
            if (dataUrl == null || dataUrl.isBlank()) return null;
            if (dataUrl.length() > 900_000) throw new ValidationException("Branding asset exceeds the allowed request size");
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:image/") || comma < 0) throw new ValidationException("Branding asset must be a PNG or WebP data URL");
            String header = dataUrl.substring(5, comma);
            if (!header.toLowerCase().endsWith(";base64")) throw new ValidationException("Branding asset must use base64 encoding");
            String mediaType = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            try {
                byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
                return new BrandingUpdate.BrandingAssetUpdate(bytes, mediaType, false);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Branding asset is not valid base64 content");
            }
        }
    }

    public record BrandingUpdateRequest(String applicationName, String shortName, String organizationName,
                                        String pageTitle, String accentColor, String secondaryColor,
                                        BrandingAssetRequest primaryLogo, BrandingAssetRequest compactLogo,
                                        BrandingAssetRequest favicon) {
        BrandingUpdate toUpdate() {
            return new BrandingUpdate(applicationName, shortName, organizationName, pageTitle, accentColor,
                    secondaryColor, primaryLogo == null ? null : primaryLogo.toUpdate(),
                    compactLogo == null ? null : compactLogo.toUpdate(), favicon == null ? null : favicon.toUpdate());
        }
    }

    public record BrandingConfigurationResponse(UUID id, UUID tenantId, String applicationName, String shortName,
                                                String organizationName, String pageTitle, String accentColor,
                                                String secondaryColor, String primaryLogoDataUrl,
                                                String compactLogoDataUrl, String faviconDataUrl,
                                                UUID updatedBy, String updatedAt) {
        static BrandingConfigurationResponse from(BrandingConfiguration c) {
            if (c == null) return null;
            return new BrandingConfigurationResponse(c.id(), c.tenantId() == null ? null : c.tenantId().value(),
                    c.applicationName(), c.shortName(), c.organizationName(), c.pageTitle(), c.accentColor(),
                    c.secondaryColor(), dataUrl(c.primaryLogo(), c.primaryLogoMediaType()),
                    dataUrl(c.compactLogo(), c.compactLogoMediaType()), dataUrl(c.favicon(), c.faviconMediaType()),
                    c.updatedBy(), c.updatedAt().toString());
        }
    }

    public record EffectiveBrandingResponse(UUID tenantId, String applicationName, String shortName,
                                            String organizationName, String pageTitle, String accentColor,
                                            String secondaryColor, String primaryLogoDataUrl,
                                            String compactLogoDataUrl, String faviconDataUrl) {
        static EffectiveBrandingResponse from(EffectiveBranding b) {
            return new EffectiveBrandingResponse(b.tenantId() == null ? null : b.tenantId().value(), b.applicationName(),
                    b.shortName(), b.organizationName(), b.pageTitle(), b.accentColor(), b.secondaryColor(),
                    dataUrl(b.primaryLogo(), b.primaryLogoMediaType()), dataUrl(b.compactLogo(), b.compactLogoMediaType()),
                    dataUrl(b.favicon(), b.faviconMediaType()));
        }
    }

    public record TenantBrandingResponse(String tenantId, String tenantName, String tenantSlug,
                                         boolean customBranding) {}

    public record TenantBrandingDetailResponse(UUID tenantId, boolean customBranding,
                                               BrandingConfigurationResponse configuration) {}

    public record BrandingOverviewResponse(BrandingConfigurationResponse deployment, List<TenantBrandingResponse> tenants) {
        static BrandingOverviewResponse from(BrandingOverview overview) {
            return new BrandingOverviewResponse(BrandingConfigurationResponse.from(overview.deployment()),
                    overview.tenants().stream().map(t -> new TenantBrandingResponse(t.tenantId(), t.tenantName(),
                            t.tenantSlug(), t.customBranding())).toList());
        }
    }

    private static String dataUrl(byte[] bytes, String mediaType) {
        if (bytes == null || bytes.length == 0 || mediaType == null) return null;
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
