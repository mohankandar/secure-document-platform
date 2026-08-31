package com.securedoc.platform.api.security;

import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaBusinessIdentifierDefinitionRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataBusinessIdentifierDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantScopedMutationGuardTest {
    @Test
    void identifierUpdateCannotReuseAnIdOwnedByAnotherTenant() {
        SpringDataBusinessIdentifierDefinitionRepository repository = mock(SpringDataBusinessIdentifierDefinitionRepository.class);
        UUID definitionId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repository.findByIdAndTenantId(definitionId, tenantB)).thenReturn(Optional.empty());
        when(repository.existsById(definitionId)).thenReturn(true);

        var adapter = new JpaBusinessIdentifierDefinitionRepositoryAdapter(repository);
        var definition = new BusinessIdentifierDefinition(
                BusinessIdentifierDefinitionId.of(definitionId), TenantId.of(tenantB),
                "contract_number", "Contract Number", true, true, true, true, true, 0, true,
                Instant.now(), Instant.now());

        assertThrows(IllegalStateException.class, () -> adapter.save(definition));
    }
}
