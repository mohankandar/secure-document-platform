package com.securedoc.platform.api.policy;

import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.config.ApplicationServiceConfig;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0052: whether one AI request may cover several pages for a tenant, through the real wiring. Find batches share
 * pages only then. Any provider the tenant may use counts (failover included). No AI server is called.
 */
class AiPagesPerRequestResolutionTest {
    private final TenantId tenant = TenantId.generate();
    private final TenantId other = TenantId.generate();

    @Test
    void ollamaFollowsItsConfiguredPagesPerRequest() {
        assertFalse(port(p -> p.setOllamaPageBatchSize(1)).mayCoverSeveralPages(tenant));
        assertTrue(port(p -> p.setOllamaPageBatchSize(4)).mayCoverSeveralPages(tenant));
        // 0 and negative values are used as one page per request.
        assertFalse(port(p -> p.setOllamaPageBatchSize(0)).mayCoverSeveralPages(tenant));
    }

    @Test
    void theOneRequestPerPageAdaptersNeverCoverSeveralPages() {
        for (String provider : List.of("openai-compatible", "vllm", "heuristic")) {
            assertFalse(port(p -> {
                p.setProvider(provider);
                p.setOllamaPageBatchSize(4);
            }).mayCoverSeveralPages(tenant), provider);
        }
    }

    @Test
    void aPoolFollowsItsNodes() {
        for (int size : new int[]{1, 4}) {
            boolean several = port(p -> {
                p.setPoolEnabled(true);
                p.setPoolNodeUrls("a=http://127.0.0.1:1,b=http://127.0.0.1:2");
                p.setOllamaPageBatchSize(size);
            }).mayCoverSeveralPages(tenant);
            assertTrue(several == (size > 1));
        }
    }

    @Test
    void aFailoverProviderThatSendsSeveralPagesKeepsTheSharedPages() {
        assertTrue(port(p -> {
            p.setProvider("openai-compatible");
            p.setOllamaPageBatchSize(4);
            secondary(p, "ollama");
        }).mayCoverSeveralPages(tenant));
        assertFalse(port(p -> {
            p.setOllamaPageBatchSize(1);
            secondary(p, "openai-compatible");
        }).mayCoverSeveralPages(tenant));
        assertTrue(port(p -> {
            p.setOllamaPageBatchSize(4);
            secondary(p, "openai-compatible");
        }).mayCoverSeveralPages(tenant), "the primary sends several");
    }

    @Test
    void aTenantsOwnProfileDecidesForThatTenantOnly() {
        SensitiveContentAnalysisPort multi = port(p -> {
            p.setOllamaPageBatchSize(1);
            profile(p, 4);
        });
        assertTrue(multi.mayCoverSeveralPages(tenant));
        assertFalse(multi.mayCoverSeveralPages(other));

        SensitiveContentAnalysisPort single = port(p -> {
            p.setOllamaPageBatchSize(4);
            profile(p, 1);
        });
        assertFalse(single.mayCoverSeveralPages(tenant));
        assertTrue(single.mayCoverSeveralPages(other));

        SensitiveContentAnalysisPort inherited = port(p -> {
            p.setOllamaPageBatchSize(4);
            profile(p, 0);
        });
        assertTrue(inherited.mayCoverSeveralPages(tenant), "a profile without a size uses the platform's");
    }

    @Test
    void aTenantWithNoProviderAndAProviderThatDoesNotSayKeepTheSharedPages() {
        assertTrue(port(p -> {
            p.setOllamaPageBatchSize(1);
            p.setRequireTenantProfile(true);
        }).mayCoverSeveralPages(tenant));
        SensitiveContentAnalysisPort unknown = request -> new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        assertTrue(unknown.mayCoverSeveralPages(tenant));
    }

    private static SensitiveContentAnalysisPort port(Consumer<AiRuntimeProperties> settings) {
        AiRuntimeProperties properties = new AiRuntimeProperties();
        properties.setProvider("ollama");
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setModel("test-model");
        settings.accept(properties);
        return new ApplicationServiceConfig().sensitiveContentAnalysisPort(properties,
                new SelectiveRetryExecutor(1, Duration.ZERO));
    }

    private static void secondary(AiRuntimeProperties p, String provider) {
        p.setSecondaryEnabled(true);
        p.setSecondaryProvider(provider);
        p.setSecondaryBaseUrl("http://127.0.0.1:2");
        p.setSecondaryModel("test-model");
    }

    private void profile(AiRuntimeProperties p, int pagesPerRequest) {
        AiRuntimeProperties.TenantProfile profile = new AiRuntimeProperties.TenantProfile();
        profile.setProvider("ollama");
        profile.setBaseUrl("http://127.0.0.1:3");
        profile.setModel("test-model");
        profile.setOllamaPageBatchSize(pagesPerRequest);
        p.setTenantProfiles(Map.of(tenant.toString(), profile));
    }
}
