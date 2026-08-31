package com.securedoc.platform.application.operations.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.operations.model.DashboardColumnSetting;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.DashboardColumnSettingsRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Business Admin ownership of which dashboard columns are permitted and initially visible. */
public final class DashboardColumnConfigurationService {
    private static final List<CatalogColumn> SYSTEM_COLUMNS = List.of(
            new CatalogColumn("received", "Received", "SYSTEM", false, true),
            new CatalogColumn("stage", "Stage", "SYSTEM", false, true),
            new CatalogColumn("documents", "Documents", "SYSTEM", false, true),
            new CatalogColumn("attention", "Attention", "SYSTEM", false, true),
            new CatalogColumn("intakeSource", "Intake Source", "SYSTEM", false, false),
            new CatalogColumn("exportStatus", "Export Status", "SYSTEM", false, false),
            new CatalogColumn("lastUpdated", "Last Updated", "SYSTEM", false, false),
            new CatalogColumn("exportCycles", "Export Cycles", "SYSTEM", false, false)
    );

    private final BusinessIdentifierDefinitionRepository identifierRepository;
    private final DashboardColumnSettingsRepository settingsRepository;
    private final AuditService auditService;

    public DashboardColumnConfigurationService(BusinessIdentifierDefinitionRepository identifierRepository,
                                               DashboardColumnSettingsRepository settingsRepository,
                                               AuditService auditService) {
        this.identifierRepository = Objects.requireNonNull(identifierRepository);
        this.settingsRepository = Objects.requireNonNull(settingsRepository);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public List<DashboardAdminColumn> getConfiguration(TenantId tenantId) {
        List<CatalogColumn> catalog = catalog(tenantId);
        Map<String, DashboardColumnSetting> saved = new HashMap<>();
        for (DashboardColumnSetting setting : settingsRepository.findByTenantId(tenantId)) saved.put(setting.key(), setting);

        List<DashboardAdminColumn> result = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            CatalogColumn column = catalog.get(i);
            DashboardColumnSetting setting = saved.get(column.key());
            boolean allowed = setting == null || setting.allowed();
            boolean defaultVisible = setting == null ? column.defaultVisible() : setting.defaultVisible();
            int order = setting == null ? i : setting.displayOrder();
            if (column.primary()) {
                allowed = true;
                defaultVisible = true;
            }
            result.add(new DashboardAdminColumn(column.key(), column.label(), column.kind(), column.primary(),
                    allowed, allowed && defaultVisible, order));
        }
        return result.stream().sorted(Comparator.comparingInt(DashboardAdminColumn::displayOrder)
                .thenComparing(DashboardAdminColumn::label)).toList();
    }

    public void updateConfiguration(TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                    List<DashboardColumnSetting> requested) {
        List<CatalogColumn> catalog = catalog(tenantId);
        Map<String, CatalogColumn> byKey = new HashMap<>();
        catalog.forEach(column -> byKey.put(column.key(), column));
        if (requested == null || requested.isEmpty()) throw new ValidationException("Dashboard column configuration is required");
        if (requested.size() != catalog.size()) throw new ValidationException("Dashboard column configuration must include every available column");

        Set<String> seen = new HashSet<>();
        List<DashboardColumnSetting> normalized = new ArrayList<>();
        int order = 0;
        for (DashboardColumnSetting setting : requested) {
            CatalogColumn column = byKey.get(setting.key());
            if (column == null || !seen.add(setting.key())) throw new ValidationException("Invalid or duplicate dashboard column");
            if (setting.defaultVisible() && !setting.allowed()) throw new ValidationException("A default dashboard column must also be allowed");
            if (column.primary() && (!setting.allowed() || !setting.defaultVisible())) {
                throw new ValidationException("Primary business identifier must remain allowed and visible by default");
            }
            normalized.add(new DashboardColumnSetting(setting.key(), setting.allowed(), setting.defaultVisible(), order++));
        }
        if (seen.size() != catalog.size()) throw new ValidationException("Dashboard column configuration is incomplete");
        if (normalized.stream().noneMatch(DashboardColumnSetting::defaultVisible)) {
            throw new ValidationException("At least one dashboard column must be visible by default");
        }

        settingsRepository.replaceForTenant(tenantId, List.copyOf(normalized));
        auditService.recordSuccess(tenantId, actorId, AuditAction.DASHBOARD_CONFIGURATION_CHANGED,
                "DASHBOARD_CONFIGURATION", tenantId.value().toString(), correlationId,
                "Dashboard allowed/default columns updated");
    }

    private List<CatalogColumn> catalog(TenantId tenantId) {
        List<CatalogColumn> result = new ArrayList<>();
        for (BusinessIdentifierDefinition definition : identifierRepository.findActiveByTenantId(tenantId)) {
            if (definition.displayable()) {
                result.add(new CatalogColumn("identifier:" + definition.key(), definition.label(), "IDENTIFIER",
                        definition.primary(), true));
            }
        }
        result.addAll(SYSTEM_COLUMNS);
        return result;
    }

    private record CatalogColumn(String key, String label, String kind, boolean primary, boolean defaultVisible) {}

    public record DashboardAdminColumn(String key, String label, String kind, boolean primary,
                                       boolean allowed, boolean defaultVisible, int displayOrder) {}
}
