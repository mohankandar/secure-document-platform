package com.securedoc.platform.api.config;

import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.application.casepage.CasePageSettingsService;
import com.securedoc.platform.application.caseview.CaseViewService;
import com.securedoc.platform.application.port.out.CasePageSettingsStore;
import com.securedoc.platform.application.port.out.CaseViewStore;
import com.securedoc.platform.application.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Task 0108: the Cases page's View menu, saved per person on the server, and the entity's Cases-page settings. */
@Configuration
public class CaseViewConfig {
    @Bean
    CasePageSettingsService casePageSettingsService(CasePageSettingsStore store, AuditService audit,
                                                    @Value("${securedoc.cases.empty-case-expiry-days:7}") int days) {
        return new CasePageSettingsService(store, audit, days);
    }

    @Bean
    CaseViewService caseViewService(CaseViewStore store, CaseFieldSettings fields, CasePageSettingsService casePage) {
        return new CaseViewService(store, fields::active, tenant -> {
            CasePageSettings settings = casePage.read(tenant);
            return new CaseViewService.Waiting(settings.waitAmberMinutes(), settings.waitRedMinutes());
        });
    }
}
