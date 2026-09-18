package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.business.admin.ReportingConfiguration;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class JpaReportingConfigurationRepositoryAdapter implements ReportingConfigurationRepository {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public ReportingConfiguration findByTenantId(TenantId tenantId) {
        UUID tenant = tenantId.value();
        Query settingsQuery = entityManager.createNativeQuery("""
                select delivery_target_minutes, delivery_target_percent,
                       straight_through_target_percent, review_wait_target_minutes
                  from reporting_tenant_settings where tenant_id=:tenant
                """);
        settingsQuery.setParameter("tenant", tenant);
        @SuppressWarnings("unchecked")
        List<Object[]> settingsRows = settingsQuery.getResultList();

        int deliveryMinutes = ReportingConfiguration.DEFAULT_DELIVERY_TARGET_MINUTES;
        double deliveryPercent = ReportingConfiguration.DEFAULT_DELIVERY_TARGET_PERCENT;
        double straightThroughPercent = ReportingConfiguration.DEFAULT_STRAIGHT_THROUGH_TARGET_PERCENT;
        int reviewWaitMinutes = ReportingConfiguration.DEFAULT_REVIEW_WAIT_TARGET_MINUTES;
        if (!settingsRows.isEmpty()) {
            Object[] row = settingsRows.get(0);
            deliveryMinutes = ((Number) row[0]).intValue();
            deliveryPercent = ((Number) row[1]).doubleValue();
            straightThroughPercent = ((Number) row[2]).doubleValue();
            reviewWaitMinutes = ((Number) row[3]).intValue();
        }

        Query dimensionsQuery = entityManager.createNativeQuery("""
                select definition_id, dimension_key, dimension_label, enabled_flag, display_order
                  from reporting_dimension_definitions
                 where tenant_id=:tenant
                 order by display_order, dimension_label
                """);
        dimensionsQuery.setParameter("tenant", tenant);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = dimensionsQuery.getResultList();
        List<ReportingConfiguration.Dimension> dimensions = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            dimensions.add(new ReportingConfiguration.Dimension(
                    row[0] instanceof UUID id ? id : UUID.fromString(row[0].toString()),
                    row[1].toString(), row[2].toString(), Boolean.TRUE.equals(row[3]),
                    ((Number) row[4]).intValue()));
        }
        return new ReportingConfiguration(dimensions, deliveryMinutes, deliveryPercent,
                straightThroughPercent, reviewWaitMinutes);
    }

    @Override
    @Transactional
    public void save(TenantId tenantId, ReportingConfiguration configuration) {
        UUID tenant = tenantId.value();
        Query settings = entityManager.createNativeQuery("""
                insert into reporting_tenant_settings (
                    tenant_id, delivery_target_minutes, delivery_target_percent,
                    straight_through_target_percent, review_wait_target_minutes, updated_at
                ) values (:tenant,:deliveryMinutes,:deliveryPercent,:straightThroughPercent,:reviewWaitMinutes,now())
                on conflict (tenant_id) do update set
                    delivery_target_minutes=excluded.delivery_target_minutes,
                    delivery_target_percent=excluded.delivery_target_percent,
                    straight_through_target_percent=excluded.straight_through_target_percent,
                    review_wait_target_minutes=excluded.review_wait_target_minutes,
                    updated_at=now()
                """);
        settings.setParameter("tenant", tenant);
        settings.setParameter("deliveryMinutes", configuration.deliveryTargetMinutes());
        settings.setParameter("deliveryPercent", configuration.deliveryTargetPercent());
        settings.setParameter("straightThroughPercent", configuration.straightThroughTargetPercent());
        settings.setParameter("reviewWaitMinutes", configuration.reviewWaitTargetMinutes());
        settings.executeUpdate();

        entityManager.createNativeQuery("delete from reporting_dimension_definitions where tenant_id=:tenant")
                .setParameter("tenant", tenant).executeUpdate();
        for (ReportingConfiguration.Dimension dimension : configuration.dimensions()) {
            Query q = entityManager.createNativeQuery("""
                    insert into reporting_dimension_definitions (
                        tenant_id, definition_id, dimension_key, dimension_label,
                        enabled_flag, display_order, updated_at
                    ) values (:tenant,:definitionId,:key,:label,:enabled,:displayOrder,now())
                    """);
            q.setParameter("tenant", tenant);
            q.setParameter("definitionId", dimension.definitionId());
            q.setParameter("key", dimension.key());
            q.setParameter("label", dimension.label());
            q.setParameter("enabled", dimension.enabled());
            q.setParameter("displayOrder", dimension.displayOrder());
            q.executeUpdate();
        }
        // Capture/backfill approved dimensions immediately for retained facts.
        entityManager.createNativeQuery("select reporting_backfill_tenant_dimensions(:tenant)")
                .setParameter("tenant", tenant).getSingleResult();
    }
}
