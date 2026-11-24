package com.securedoc.platform.api.monitoring;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.spring.AppVersions;
import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.config.OllamaInferencePoolConfiguration;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Task 0057: LexaVault's own checks beside platform-monitoring's (database, login system, licence): the model server and the
 * document storage, and LexaVault's version in the diagnostic bundle.
 */
@Configuration
public class MonitoringConfig {

    /** No model server is used with the heuristic provider (development only). */
    static final class UsesModelServer implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"heuristic".equalsIgnoreCase(context.getEnvironment().getProperty("securedoc.ai.provider", "ollama").trim());
        }
    }

    @Bean
    @Conditional(UsesModelServer.class)
    DependencyCheck modelServerCheck(AiRuntimeProperties ai,
                                     @Value("${securedoc.monitoring.model-server-required:false}") boolean required) {
        return new ModelServerCheck(servers(ai), required);
    }

    static List<ModelServerCheck.Server> servers(AiRuntimeProperties ai) {
        boolean ollama = "ollama".equalsIgnoreCase(ai.getProvider().trim());
        List<ModelServerCheck.Server> out = new ArrayList<>();
        if (ollama && ai.isPoolEnabled()) {
            OllamaInferencePoolConfiguration.parse(ai.getPoolNodeUrls()).forEach(node -> out.add(
                    new ModelServerCheck.Server(URI.create(base(node.baseUrl()) + "/api/tags"), ai.getModel(), "")));
        } else if (ollama) {
            out.add(new ModelServerCheck.Server(URI.create(base(ai.getBaseUrl()) + "/api/tags"), ai.getModel(), ""));
        } else {
            String base = base(ai.getBaseUrl());
            if (base.endsWith("/chat/completions")) base = base.substring(0, base.length() - "/chat/completions".length());
            out.add(new ModelServerCheck.Server(URI.create(base + (base.endsWith("/v1") ? "/models" : "/v1/models")),
                    ai.getModel(), ai.getApiKey()));
        }
        return out;
    }

    private static String base(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Bean
    DependencyCheck processingStorageCheck(ProcessingStorageConfigurationRepository repository,
                                           @Value("${securedoc.monitoring.storage-required:true}") boolean required) {
        return new ProcessingStorageCheck(() -> Stream.concat(repository.findDeploymentDefault().stream(),
                        repository.findTenantOverrides().stream())
                .map(ProcessingStorageAssignment::providerId).distinct()
                .flatMap(id -> repository.findProvider(id).stream())
                .filter(p -> p.status() != ProcessingStorageProviderStatus.RETIRED)
                .map(ProcessingStorageProvider::rootPath).toList(), required);
    }

    @Bean
    AppVersions lexaVaultVersions(@Value("${securedoc.product-telemetry.application-version:unknown}") String version) {
        return () -> Map.of("lexavault", version);
    }
}
