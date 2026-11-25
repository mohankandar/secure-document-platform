package com.securedoc.platform.api.config;

import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.securedoc.platform.api.notifications.AppSecretResolver;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.pipeline.ExportMode;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.infrastructure.delivery.DestinationProfiles;
import com.securedoc.platform.infrastructure.delivery.ProfileDestinationDeliveryAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.stream.Collectors;

/**
 * Task 0087: delivery to a destination through its published profile, on platform-integration. Every call goes through
 * the installation's outbound rules ({@code platform.outbound.*}), every secret through the app's resolver. The
 * configurations are LexaVault's connections, so platform-integration's own tables and background work are not used.
 */
@Configuration
public class DestinationDeliveryConfig {

    @Bean
    DestinationProfiles destinationProfiles(@Value("${securedoc.delivery.profile-folder:}") String folder,
                                            Installation installation, ObjectProvider<CaseFieldService> caseFields) {
        return new DestinationProfiles(folder, installation, AppSecretResolver::entityRef,
                tenant -> caseFields.getObject().active(tenant).stream().map(d -> d.key().value()).collect(Collectors.toSet()));
    }

    /** Not closed with the bean: the outbound client is the app's, and closing the engine would close it for the others. */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "document-store")
    CallEngine destinationCallEngine(Outbound outbound, DestinationProfiles profiles, AppSecretResolver secrets) {
        return new CallEngine(outbound, profiles.secretResolver(secrets), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "document-store")
    ProfileDestinationDeliveryAdapter destinationDelivery(IntegrationConnectionRepository connections,
                                                          DestinationProfiles profiles, CallEngine engine,
                                                          @Value("${securedoc.processing.export-mode:inline}") String exportMode) {
        return new ProfileDestinationDeliveryAdapter(connections, profiles, engine,
                ExportMode.parse(exportMode) == ExportMode.JOB, Clock.systemUTC());
    }
}
