package com.securedoc.platform.api.config;

import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationAcknowledgementMode;
import com.securedoc.platform.domain.integration.IntegrationAuthenticationType;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationDirection;
import com.securedoc.platform.domain.integration.IntegrationProviderType;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.infrastructure.persistence.entity.TenantEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantCapabilityRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class LocalDemoBootstrapScannerIntegrationTest {
    private static final UUID DEMO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final TenantId TENANT_ID = TenantId.of(DEMO_ID);
    private static final String PRINCIPAL = "scanner-bridge-local";

    @Test
    void differentExistingScannerDoesNotSuppressRequiredLocalPrincipalBinding() {
        Fixture f = fixture();
        IntegrationConnection otherScanner = scanner("other-scanner-client", true);
        when(f.integrations.findEnabledScannerSourceByTenantIdAndClientPrincipal(TENANT_ID, PRINCIPAL))
                .thenReturn(Optional.empty());
        when(f.integrations.findByTenantId(TENANT_ID)).thenReturn(List.of(otherScanner));

        f.bootstrap.run(mock(ApplicationArguments.class));

        verify(f.integrations).save(argThat(connection -> connection.providerType() == IntegrationProviderType.SCANNER
                && connection.enabled()
                && PRINCIPAL.equals(connection.clientPrincipal())));
    }

    @Test
    void bootstrapDoesNotOverwriteOrDuplicateAnExistingPrincipalBinding() {
        Fixture f = fixture();
        IntegrationConnection disabledSamePrincipal = scanner(PRINCIPAL, false);
        when(f.integrations.findEnabledScannerSourceByTenantIdAndClientPrincipal(TENANT_ID, PRINCIPAL))
                .thenReturn(Optional.empty());
        when(f.integrations.findByTenantId(TENANT_ID)).thenReturn(List.of(disabledSamePrincipal));

        f.bootstrap.run(mock(ApplicationArguments.class));

        verify(f.integrations, never()).save(any(IntegrationConnection.class));
    }

    private Fixture fixture() {
        SpringDataTenantRepository tenants = mock(SpringDataTenantRepository.class);
        SpringDataTenantCapabilityRepository capabilities = mock(SpringDataTenantCapabilityRepository.class);
        BusinessIdentifierDefinitionRepository identifiers = mock(BusinessIdentifierDefinitionRepository.class);
        TenantRedactionPolicyRepository redactionPolicies = mock(TenantRedactionPolicyRepository.class);
        IntegrationConnectionRepository integrations = mock(IntegrationConnectionRepository.class);

        when(tenants.findById(DEMO_ID)).thenReturn(Optional.of(new TenantEntity()));
        BusinessIdentifierDefinition currentDefinition = BusinessIdentifierDefinition.create(TENANT_ID,
                "contract_number", "Contract Number", true, true, true, true, true, 1,
                BusinessIdentifierValidationType.NUMERIC, 12, null, null,
                BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null);
        when(identifiers.findActiveByTenantId(TENANT_ID)).thenReturn(List.of(currentDefinition));
        when(identifiers.findAllByTenantId(TENANT_ID)).thenReturn(List.of(currentDefinition));
        when(redactionPolicies.findByTenantId(TENANT_ID)).thenReturn(Optional.of(TenantRedactionPolicy.defaults(TENANT_ID)));

        LocalDemoBootstrap bootstrap = new LocalDemoBootstrap(tenants, capabilities, identifiers, redactionPolicies,
                integrations, DEMO_ID.toString(), "Local Demo Tenant", "local-demo", PRINCIPAL);
        return new Fixture(bootstrap, integrations);
    }

    private IntegrationConnection scanner(String principal, boolean enabled) {
        return IntegrationConnection.create(TENANT_ID, "Scanner " + principal, null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, principal, null, null, null,
                60, 2, 24, 1, 2L * 1024L * 1024L * 1024L, 10_000,
                IntegrationAcknowledgementMode.NONE, enabled, Instant.parse("2026-09-10T12:00:00Z"));
    }

    private record Fixture(LocalDemoBootstrap bootstrap, IntegrationConnectionRepository integrations) { }
}
