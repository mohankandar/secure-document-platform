package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.operations.model.DashboardColumnSetting;
import com.securedoc.platform.application.port.out.DashboardColumnSettingsRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.DashboardColumnSettingEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDashboardColumnSettingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class JpaDashboardColumnSettingsRepositoryAdapter implements DashboardColumnSettingsRepository {
    private final SpringDataDashboardColumnSettingRepository repository;

    public JpaDashboardColumnSettingsRepositoryAdapter(SpringDataDashboardColumnSettingRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DashboardColumnSetting> findByTenantId(TenantId tenantId) {
        return repository.findByTenantIdOrderByDisplayOrderAsc(tenantId.value()).stream()
                .map(entity -> new DashboardColumnSetting(entity.getColumnKey(), entity.isAllowed(),
                        entity.isDefaultVisible(), entity.getDisplayOrder()))
                .toList();
    }

    @Override
    @Transactional
    public void replaceForTenant(TenantId tenantId, List<DashboardColumnSetting> settings) {
        repository.deleteByTenantId(tenantId.value());
        repository.flush();
        List<DashboardColumnSettingEntity> entities = settings.stream().map(setting -> {
            DashboardColumnSettingEntity entity = new DashboardColumnSettingEntity();
            entity.setId(UUID.randomUUID());
            entity.setTenantId(tenantId.value());
            entity.setColumnKey(setting.key());
            entity.setAllowed(setting.allowed());
            entity.setDefaultVisible(setting.defaultVisible());
            entity.setDisplayOrder(setting.displayOrder());
            return entity;
        }).toList();
        repository.saveAll(entities);
        repository.flush();
    }
}
