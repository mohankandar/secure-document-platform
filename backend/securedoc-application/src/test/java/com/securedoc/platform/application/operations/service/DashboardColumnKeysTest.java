package com.securedoc.platform.application.operations.service;

import com.securedoc.platform.application.operations.model.DashboardColumnSetting;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.DashboardColumnSettingsRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0038: saved Cases column choices are kept by each column's stable key, so they survive a renamed identifier and
 * a column added later; the order never follows a label or a position in the catalog.
 */
class DashboardColumnKeysTest {
    private final TenantId tenant = TenantId.generate();
    private final Definitions definitions = new Definitions();
    private final Settings settings = new Settings();
    private final DashboardColumnConfigurationService service =
            new DashboardColumnConfigurationService(definitions, settings, new AuditService(r -> { }));

    @Test
    void aSavedChoiceSurvivesARenameOfItsIdentifier() {
        definitions.values.add(identifier("loan_number", "Loan number", true, 1));
        definitions.values.add(identifier("borrower_ref", "Borrower ref", false, 2));
        List<DashboardColumnSetting> choice = reversedWithBorrowerHidden();
        service.updateConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), choice);
        List<String> before = keys();

        // The Business Admin renames the identifier (its key cannot change).
        definitions.values.set(1, definitions.values.get(1).reconfigure("Customer reference", false, false, true, false,
                true, 2));
        assertEquals(before, keys(), "same order after the rename");
        var renamed = service.getConfiguration(tenant).stream().filter(c -> c.key().equals("identifier:borrower_ref"))
                .findFirst().orElseThrow();
        assertEquals("Customer reference", renamed.label());
        assertFalse(renamed.defaultVisible(), "still hidden by default");
    }

    @Test
    void aColumnAddedAfterTheSaveGoesAfterTheSavedOnesNotIntoTheirPlaces() {
        definitions.values.add(identifier("loan_number", "Loan number", true, 1));
        definitions.values.add(identifier("borrower_ref", "Borrower ref", false, 2));
        service.updateConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), reversedWithBorrowerHidden());
        List<String> saved = keys();

        definitions.values.add(identifier("aaa_first_by_name", "AAA first by name", false, 0));
        List<String> after = keys();
        assertEquals(saved, after.subList(0, saved.size()), "the saved order is kept as it was");
        assertEquals("identifier:aaa_first_by_name", after.get(after.size() - 1));
    }

    private List<DashboardColumnSetting> reversedWithBorrowerHidden() {
        List<DashboardColumnSetting> choice = new ArrayList<>();
        List<DashboardColumnConfigurationService.DashboardAdminColumn> columns = new ArrayList<>(
                service.getConfiguration(tenant));
        java.util.Collections.reverse(columns);
        for (var c : columns) {
            boolean hide = c.key().equals("identifier:borrower_ref");
            choice.add(new DashboardColumnSetting(c.key(), true, !hide && (c.primary() || c.defaultVisible()), 0));
        }
        return choice;
    }

    private List<String> keys() {
        return service.getConfiguration(tenant).stream().map(DashboardColumnConfigurationService.DashboardAdminColumn::key)
                .toList();
    }

    private BusinessIdentifierDefinition identifier(String key, String label, boolean primary, int order) {
        return BusinessIdentifierDefinition.create(tenant, key, label, primary, primary, true, false, true, order,
                BusinessIdentifierValidationType.ALPHANUMERIC, null, null, null,
                BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null);
    }

    private static final class Definitions implements BusinessIdentifierDefinitionRepository {
        final List<BusinessIdentifierDefinition> values = new ArrayList<>();
        public List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId) { return List.copyOf(values); }
        public List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId) { return List.copyOf(values); }
        public void save(BusinessIdentifierDefinition definition) { values.add(definition); }
        public void saveAll(List<BusinessIdentifierDefinition> definitions) { values.clear(); values.addAll(definitions); }
    }

    private static final class Settings implements DashboardColumnSettingsRepository {
        List<DashboardColumnSetting> saved = List.of();
        public List<DashboardColumnSetting> findByTenantId(TenantId tenantId) { return saved; }
        public void replaceForTenant(TenantId tenantId, List<DashboardColumnSetting> settings) { saved = settings; }
    }
}
