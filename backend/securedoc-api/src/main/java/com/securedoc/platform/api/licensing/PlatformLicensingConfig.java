package com.securedoc.platform.api.licensing;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.spring.notifications.ActivatedSlots;
import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.RoleId;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 0057: platform-licensing in LexaVault. platform-licensing-spring checks the tables and the stored licence at start
 * (a missing or refused licence stops the start with its code); these are LexaVault's own parts.
 *
 * <p>The licensing notification events are registered with LexaVault's roles. Nothing is sent until notifications
 * themselves are wired (there is no notifier bean yet), so the licensing check that publishes does not run.
 */
@Configuration
public class PlatformLicensingConfig {

    static final RoleId IT_ADMIN = RoleId.of("TENANT_IT_ADMIN");
    static final RoleId BUSINESS_ADMIN = RoleId.of("BUSINESS_ADMIN");

    @Bean(destroyMethod = "close")
    public PlatformLicence platformLicence(LicenceService licences, AuditService auditService) {
        return new PlatformLicence(licences::status, licences::productLicensed, auditService);
    }

    @Bean
    public PlatformUsageMeter platformUsageMeter(PlatformLicence licence, DataSource dataSource,
                                                 PlatformTransactionManager transactions) {
        return new PlatformUsageMeter(licence, dataSource, transactions);
    }

    @Bean
    SmartInitializingSingleton platformLicenceReadings(PlatformLicence licence, PlatformUsageMeter meter) {
        return () -> licence.start(meter::recordWaiting);
    }

    /** LexaVault's event catalogue; its own events join it with notifications. */
    @Bean
    EventCatalogue lexaVaultEventCatalogue() {
        return LicensingEvents.register(EventCatalogue.builder(IT_ADMIN), IT_ADMIN, BUSINESS_ADMIN).build();
    }

    /** The entity slots a Business Admin has activated, with LexaVault's entity ids (per-entity licensing events). */
    @Bean
    ActivatedSlots activatedEntitySlots(JdbcTemplate jdbc) {
        return tenantId -> {
            Map<String, String> slots = new LinkedHashMap<>();
            for (String slot : jdbc.queryForList("select slot_id from idn_entity where tenant_id = ? and status = 'ACTIVE'"
                    + " order by slot_id", String.class, tenantId)) {
                slots.put(slot, TenantId.of(EntityUuid.fromSlotId(slot)).toString());
            }
            return slots;
        };
    }
}
