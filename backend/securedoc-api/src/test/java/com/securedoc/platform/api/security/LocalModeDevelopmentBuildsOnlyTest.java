package com.securedoc.platform.api.security;

import com.documentplatform.licensing.LicenceBuild;
import com.securedoc.install.TestBuild;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/** Hardening: LOCAL mode only in development builds. */
class LocalModeDevelopmentBuildsOnlyTest {

    private static final String KEY_SERVICE = "platform.crypto.vault.address=https://key-service.example";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class Check {
        @Bean
        static SmartInitializingSingleton localModeOnlyInDevelopmentBuilds(ObjectProvider<SecurityProperties> properties,
                                                                          Environment environment,
                                                                          ObjectProvider<LicenceBuild> build) {
            return SecurityConfig.localModeOnlyInDevelopmentBuilds(properties, environment, build);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Production {
        @Bean
        LicenceBuild testLicenceBuild() {
            return TestBuild.production();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Development {
        @Bean
        LicenceBuild testLicenceBuild() {
            return TestBuild.development();
        }
    }

    private static Throwable deepest(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private static ApplicationContextRunner runner(Class<?> build) {
        return new ApplicationContextRunner().withUserConfiguration(Check.class, build);
    }

    @Test
    void aProductionBuildAskingForLocalModeDoesNotStart() {
        runner(Production.class).withPropertyValues("securedoc.security.mode=LOCAL").run(context -> {
            assertThat(context).hasFailed();
            assertThat(deepest(context.getStartupFailure())).hasMessage("LV-SYS-005 " + DevelopmentBuildOnly.LOCAL_MODE_REFUSED);
        });
    }

    @Test
    void aProductionBuildAskingForLocalModeDoesNotStartWithAKeyServiceEither() {
        runner(Production.class).withPropertyValues("securedoc.security.mode=LOCAL", KEY_SERVICE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(deepest(context.getStartupFailure())).hasMessageContaining(DevelopmentBuildOnly.LOCAL_MODE_REFUSED);
        });
    }

    @Test
    void anyCaseOrSpacingOfTheSettingIsRefusedTooInAProductionBuild() {
        for (String spelling : new String[] {"local", "Local", " LOCAL "}) {
            runner(Production.class).withPropertyValues("securedoc.security.mode=" + spelling, KEY_SERVICE)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void aDevelopmentBuildStillStartsInLocalMode() {
        runner(Development.class).withPropertyValues("securedoc.security.mode=LOCAL")
                .run(context -> assertThat(context).hasNotFailed());
        runner(Development.class).withPropertyValues("securedoc.security.mode=LOCAL", KEY_SERVICE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aProductionBuildWithNormalSignInStillStarts() {
        runner(Production.class).withPropertyValues("securedoc.security.mode=OIDC", KEY_SERVICE)
                .run(context -> assertThat(context).hasNotFailed());
        runner(Production.class).withPropertyValues(KEY_SERVICE).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void plainFilesUseTheSameBuildCheck() {
        ObjectProvider<LicenceBuild> production = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("b", TestBuild.production())).getBeanProvider(LicenceBuild.class);
        ObjectProvider<LicenceBuild> development = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("b", TestBuild.development())).getBeanProvider(LicenceBuild.class);
        assertThat(DevelopmentBuildOnly.isDevelopmentBuild(production)).isFalse();
        assertThat(DevelopmentBuildOnly.isDevelopmentBuild(development)).isTrue();
    }
}
