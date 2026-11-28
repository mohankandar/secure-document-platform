package com.securedoc.platform.api.config;

import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.caseview.CaseViewService;
import com.securedoc.platform.application.port.out.CaseViewStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Task 0108: the Cases page's View menu, saved per person on the server. */
@Configuration
public class CaseViewConfig {
    @Bean
    CaseViewService caseViewService(CaseViewStore store, CaseFieldSettings fields) {
        return new CaseViewService(store, fields::active);
    }
}
