package com.securedoc.platform.api.licensing;

import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.spring.notifications.ActivatedSlots;
import com.securedoc.platform.application.service.AuditService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.securedoc.platform.infrastructure.identity.EntitySlotStore;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Task 0057: platform-licensing in LexaVault. platform-licensing-spring checks the tables and the stored licence at start
 * (a missing or refused licence stops the start with its code); these are LexaVault's own parts.
 *
 * <p>The licensing notification events are in LexaVault's catalogue (task 0059, {@code LexaVaultEvents}).
 */
@Configuration
public class PlatformLicensingConfig {

    @Bean(destroyMethod = "close")
    public PlatformLicence platformLicence(LicenceService licences, AuditService auditService, EntitySlotStore entities) {
        return new PlatformLicence(licences::status, licences::productLicensed, auditService)
                .withEntityActive(entities::active);
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

    /** The entity slots a Business Admin has activated (suspended ones too), with LexaVault's entity ids. */
    @Bean
    ActivatedSlots activatedEntitySlots(EntitySlotStore entities) {
        return customer -> entities.withStore(s -> s.activated(customer));
    }
}
