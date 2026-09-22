package com.securedoc.platform.application.branding;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Product branding service. Branding is presentation configuration only and never changes tenant identity,
 * licensing identity, exported document content, audit semantics, or integration payloads.
 */
public final class BrandingService {
    public static final String DEFAULT_APPLICATION_NAME = "LexaVault";
    public static final String DEFAULT_SHORT_NAME = "LexaVault";
    public static final String DEFAULT_PAGE_TITLE = "LexaVault";
    public static final String DEFAULT_ACCENT = "#315CF4";
    public static final String DEFAULT_SECONDARY = "#6D5AE6";
    public static final int MAX_LOGO_BYTES = 512 * 1024;
    public static final int MAX_FAVICON_BYTES = 128 * 1024;

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final BrandingConfigurationRepository repository;
    private final TenantRepository tenants;
    private final AuditService auditService;

    public BrandingService(BrandingConfigurationRepository repository,
                           TenantRepository tenants,
                           AuditService auditService) {
        this.repository = Objects.requireNonNull(repository);
        this.tenants = Objects.requireNonNull(tenants);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public EffectiveBranding effective(TenantId tenantId) {
        BrandingConfiguration deployment = repository.findDeployment().orElse(null);
        BrandingConfiguration tenant = tenantId == null ? null : repository.findTenant(tenantId).orElse(null);
        return new EffectiveBranding(
                tenantId,
                first(tenant == null ? null : tenant.applicationName(), deployment == null ? null : deployment.applicationName(), DEFAULT_APPLICATION_NAME),
                first(tenant == null ? null : tenant.shortName(), deployment == null ? null : deployment.shortName(), DEFAULT_SHORT_NAME),
                first(tenant == null ? null : tenant.organizationName(), deployment == null ? null : deployment.organizationName(), null),
                first(tenant == null ? null : tenant.pageTitle(), deployment == null ? null : deployment.pageTitle(), DEFAULT_PAGE_TITLE),
                first(tenant == null ? null : tenant.accentColor(), deployment == null ? null : deployment.accentColor(), DEFAULT_ACCENT),
                first(tenant == null ? null : tenant.secondaryColor(), deployment == null ? null : deployment.secondaryColor(), DEFAULT_SECONDARY),
                firstBytes(tenant == null ? null : tenant.primaryLogo(), deployment == null ? null : deployment.primaryLogo()),
                first(tenant == null ? null : tenant.primaryLogoMediaType(), deployment == null ? null : deployment.primaryLogoMediaType(), null),
                firstBytes(tenant == null ? null : tenant.compactLogo(), deployment == null ? null : deployment.compactLogo()),
                first(tenant == null ? null : tenant.compactLogoMediaType(), deployment == null ? null : deployment.compactLogoMediaType(), null),
                firstBytes(tenant == null ? null : tenant.favicon(), deployment == null ? null : deployment.favicon()),
                first(tenant == null ? null : tenant.faviconMediaType(), deployment == null ? null : deployment.faviconMediaType(), null)
        );
    }

    public BrandingConfiguration tenantOverride(TenantId tenantId) {
        tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId.toString()));
        return repository.findTenant(tenantId).orElse(null);
    }

    public BrandingOverview overview() {
        var overrides = repository.findTenantOverrides();
        var byTenant = overrides.stream().filter(c -> c.tenantId() != null)
                .collect(java.util.stream.Collectors.toMap(c -> c.tenantId().value(), c -> c));
        List<BrandingOverview.TenantBrandingSummary> summaries = tenants.findAll().stream()
                .map(tenant -> new BrandingOverview.TenantBrandingSummary(
                        tenant.id().value().toString(), tenant.name(), tenant.slug(), byTenant.containsKey(tenant.id().value())))
                .toList();
        return new BrandingOverview(repository.findDeployment().orElse(null), summaries);
    }

    public BrandingConfiguration updateDeployment(BrandingUpdate update, ActorId actorId,
                                                  TenantId auditTenantId, CorrelationId correlationId) {
        BrandingConfiguration current = repository.findDeployment().orElse(null);
        BrandingConfiguration saved = repository.save(build(current, null, update, actorId));
        auditService.recordSuccess(auditTenantId, actorId, AuditAction.BRANDING_CONFIGURATION_CHANGED,
                "Branding", "deployment", correlationId, "Deployment UI branding changed");
        return saved;
    }

    public BrandingConfiguration updateTenant(TenantId tenantId, BrandingUpdate update, ActorId actorId,
                                              CorrelationId correlationId) {
        tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId.toString()));
        BrandingConfiguration current = repository.findTenant(tenantId).orElse(null);
        BrandingConfiguration saved = repository.save(build(current, tenantId, update, actorId));
        auditService.recordSuccess(tenantId, actorId, AuditAction.BRANDING_CONFIGURATION_CHANGED,
                "Branding", tenantId.toString(), correlationId, "Tenant UI branding changed");
        return saved;
    }

    public void clearTenant(TenantId tenantId, ActorId actorId, CorrelationId correlationId) {
        tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId.toString()));
        repository.deleteTenant(tenantId);
        auditService.recordSuccess(tenantId, actorId, AuditAction.BRANDING_CONFIGURATION_CHANGED,
                "Branding", tenantId.toString(), correlationId, "Tenant UI branding reset to deployment defaults");
    }

    private BrandingConfiguration build(BrandingConfiguration current, TenantId tenantId,
                                        BrandingUpdate update, ActorId actorId) {
        Objects.requireNonNull(update, "Branding update is required");
        String appName = text(update.applicationName(), 80, "Application name");
        String shortName = text(update.shortName(), 32, "Short name");
        String organization = text(update.organizationName(), 100, "Organization name");
        String pageTitle = text(update.pageTitle(), 100, "Page title");
        String accent = color(update.accentColor(), "Accent color");
        String secondary = color(update.secondaryColor(), "Secondary color");

        Asset primary = asset(current == null ? null : current.primaryLogo(), current == null ? null : current.primaryLogoMediaType(),
                update.primaryLogo(), MAX_LOGO_BYTES, "Primary logo");
        Asset compact = asset(current == null ? null : current.compactLogo(), current == null ? null : current.compactLogoMediaType(),
                update.compactLogo(), MAX_LOGO_BYTES, "Compact logo");
        Asset favicon = asset(current == null ? null : current.favicon(), current == null ? null : current.faviconMediaType(),
                update.favicon(), MAX_FAVICON_BYTES, "Favicon");

        return new BrandingConfiguration(
                current == null ? UUID.randomUUID() : current.id(), tenantId,
                appName, shortName, organization, pageTitle, accent, secondary,
                primary.bytes(), primary.mediaType(), compact.bytes(), compact.mediaType(), favicon.bytes(), favicon.mediaType(),
                actorId.value(), Instant.now());
    }

    private static String text(String value, int max, String label) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw new ValidationException(label + " must be " + max + " characters or fewer");
        return trimmed;
    }

    private static String color(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!HEX.matcher(normalized).matches()) throw new ValidationException(label + " must be a six-digit hex color such as #315CF4");
        if ("Accent color".equals(label) && contrastWithWhite(normalized) < 4.5d) {
            throw new ValidationException("Accent color must provide WCAG AA contrast with white button text");
        }
        return normalized;
    }

    private static double contrastWithWhite(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        double luminance = 0.2126d * linear(r / 255d) + 0.7152d * linear(g / 255d) + 0.0722d * linear(b / 255d);
        return 1.05d / (luminance + 0.05d);
    }

    private static double linear(double channel) {
        return channel <= 0.03928d ? channel / 12.92d : Math.pow((channel + 0.055d) / 1.055d, 2.4d);
    }

    private static Asset asset(byte[] currentBytes, String currentMediaType, BrandingUpdate.BrandingAssetUpdate update,
                               int maxBytes, String label) {
        if (update == null) return new Asset(currentBytes, currentMediaType);
        if (update.clear()) return new Asset(null, null);
        if (update.bytes() == null || update.bytes().length == 0) throw new ValidationException(label + " is empty");
        if (update.bytes().length > maxBytes) throw new ValidationException(label + " exceeds the allowed file size");
        String mediaType = update.mediaType() == null ? "" : update.mediaType().toLowerCase(Locale.ROOT);
        if (!mediaType.equals("image/png") && !mediaType.equals("image/webp")) {
            throw new ValidationException(label + " must be PNG or WebP");
        }
        if (mediaType.equals("image/png") && !isPng(update.bytes())) throw new ValidationException(label + " content does not match PNG format");
        if (mediaType.equals("image/webp") && !isWebp(update.bytes())) throw new ValidationException(label + " content does not match WebP format");
        return new Asset(update.bytes(), mediaType);
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a;
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static String first(String a, String b, String fallback) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : fallback;
    }

    private static byte[] firstBytes(byte[] a, byte[] b) {
        return a != null && a.length > 0 ? a : b != null && b.length > 0 ? b : null;
    }

    private record Asset(byte[] bytes, String mediaType) {}
}
