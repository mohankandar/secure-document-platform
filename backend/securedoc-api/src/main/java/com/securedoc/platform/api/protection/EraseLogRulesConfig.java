package com.securedoc.platform.api.protection;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.monitoring.DependencyCheck;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Task 0060: the erase log is never switched off, and outside a LOCAL development start it is keyed. */
@Configuration
public class EraseLogRulesConfig {

    @Bean
    InitializingBean eraseLogRules(Environment environment) {
        return () -> {
            if (!environment.getProperty("platform.erase-log.enabled", Boolean.class, true)) {
                throw new IllegalStateException("platform.erase-log.enabled=false is not possible: LexaVault erases only "
                        + "through the erase log (docs/install/ERASE_LOG.md)");
            }
            if (!LocalProtectionDefaults.local(environment)
                    && !environment.getProperty("platform.erase-log.keyed", Boolean.class, true)) {
                throw new IllegalStateException("platform.erase-log.keyed=false is not possible: the erase log is keyed with "
                        + "the key service (docs/install/ERASE_LOG.md)");
            }
        };
    }

    @Bean
    DependencyCheck eraseLogCheck(EraseGuard guard) {
        return new EraseLogCheck(guard);
    }
}
