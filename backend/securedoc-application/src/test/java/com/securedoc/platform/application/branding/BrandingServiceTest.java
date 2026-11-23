package com.securedoc.platform.application.branding;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BrandingServiceTest {

    @Test
    void tenantBrandingOverridesOnlyConfiguredFieldsAndKeepsIdentityIndependent() {
        Tenant tenant = Tenant.create("Contract Department", "contract-department-branding");
        InMemoryBrandingRepository repo = new InMemoryBrandingRepository();
        BrandingService service = service(tenant, repo);
        ActorId actor = ActorId.generate();

        service.updateDeployment(update("Acme Document Platform", "Acme Docs", "Acme Mortgage", "Acme Docs", "#6D5AE6"),
                actor, tenant.id(), CorrelationId.generate());
        service.updateTenant(tenant.id(), update("Contract Document Center", null, null, null, "#5B2C83"),
                actor, CorrelationId.generate());

        EffectiveBranding effective = service.effective(tenant.id());
        assertEquals(tenant.id(), effective.tenantId());
        assertEquals("Contract Document Center", effective.applicationName());
        assertEquals("Acme Docs", effective.shortName());
        assertEquals("Acme Mortgage", effective.organizationName());
        assertEquals("#5B2C83", effective.secondaryColor());
    }

    @Test
    void defaultBrandIsUsedWhenNoAdministrativeBrandingExists() {
        Tenant tenant = Tenant.create("Local Demo", "local-demo-branding");
        BrandingService service = service(tenant, new InMemoryBrandingRepository());
        EffectiveBranding effective = service.effective(tenant.id());
        assertEquals("LexaVault", effective.applicationName());
        assertEquals("#6D5AE6", effective.secondaryColor());
    }

    @Test
    void theAccentColourIsGoneFromBranding() {
        // Task 0038: the unused accent colour was removed from the setting, the API and the screens. Its database
        // column stays, unused (no migration).
        for (Class<?> type : new Class<?>[] {BrandingUpdate.class, BrandingConfiguration.class, EffectiveBranding.class}) {
            assertTrue(java.util.Arrays.stream(type.getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase(java.util.Locale.ROOT).contains("accent")), type.getName());
        }
    }

    @Test
    void aColourMustBeASixDigitHexColour() {
        Tenant tenant = Tenant.create("Local Demo", "local-demo-branding-hex");
        BrandingService service = service(tenant, new InMemoryBrandingRepository());
        assertThrows(ValidationException.class, () -> service.updateDeployment(
                update("Brand", "Brand", null, null, "blue"), ActorId.generate(), tenant.id(), CorrelationId.generate()));
    }

    private static BrandingUpdate update(String app, String shortName, String org, String title, String secondary) {
        return new BrandingUpdate(app, shortName, org, title, secondary, null, null, null);
    }

    private static BrandingService service(Tenant tenant, InMemoryBrandingRepository repo) {
        TenantRepository tenantRepository = new TenantRepository() {
            @Override public Optional<Tenant> findById(TenantId tenantId) { return tenant.id().equals(tenantId) ? Optional.of(tenant) : Optional.empty(); }
            @Override public Optional<Tenant> findBySlug(String slug) { return tenant.slug().equals(slug) ? Optional.of(tenant) : Optional.empty(); }
            @Override public List<Tenant> findAll() { return List.of(tenant); }
        };
        AuditPort audit = new AuditPort() { @Override public void record(AuditRecord auditRecord) { } };
        return new BrandingService(repo, tenantRepository, new AuditService(audit));
    }

    private static final class InMemoryBrandingRepository implements BrandingConfigurationRepository {
        BrandingConfiguration deployment;
        final Map<TenantId, BrandingConfiguration> overrides = new HashMap<>();
        @Override public Optional<BrandingConfiguration> findDeployment() { return Optional.ofNullable(deployment); }
        @Override public Optional<BrandingConfiguration> findTenant(TenantId tenantId) { return Optional.ofNullable(overrides.get(tenantId)); }
        @Override public List<BrandingConfiguration> findTenantOverrides() { return List.copyOf(overrides.values()); }
        @Override public BrandingConfiguration save(BrandingConfiguration configuration) {
            if (configuration.tenantId() == null) deployment = configuration; else overrides.put(configuration.tenantId(), configuration);
            return configuration;
        }
        @Override public void deleteTenant(TenantId tenantId) { overrides.remove(tenantId); }
    }
}
