package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.SensitiveContentPromptBuilder;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.securedoc.platform.application.resilience.TenantRoutingSensitiveContentAnalysisPort;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantAiPolicyAndRoutingTest {

    @Test
    void promptContainsOnlyTenantEnabledCategories() {
        SensitiveContentPromptBuilder builder = new SensitiveContentPromptBuilder();
        String prompt = builder.build(new AnalysisPolicy(Set.of(EntityType.CREDIT_SCORE), true));

        assertTrue(prompt.contains("CREDIT_SCORE"));
        assertFalse(prompt.contains("DATE_OF_BIRTH"));
        assertFalse(prompt.contains("CREDIT_CARD_NUMBER"));
        assertFalse(prompt.contains("SSN\n"));
    }

    @Test
    void compactOllamaPromptContainsOnlyTenantEnabledCategories() {
        SensitiveContentPromptBuilder builder = new SensitiveContentPromptBuilder();
        String prompt = builder.buildCompact(new AnalysisPolicy(Set.of(EntityType.CREDIT_SCORE), true));

        assertTrue(prompt.contains("CREDIT_SCORE"));
        assertFalse(prompt.contains("DATE_OF_BIRTH"));
        assertFalse(prompt.contains("CREDIT_CARD_NUMBER"));
        assertFalse(prompt.contains("SSN:"));
    }

    @Test
    void qwenPromptCarriesCompactPrecisionGuardrails() {
        SensitiveContentPromptBuilder builder = new SensitiveContentPromptBuilder();
        String prompt = builder.buildCompact(new AnalysisPolicy(Set.of(
                EntityType.SSN, EntityType.DATE_OF_BIRTH,
                EntityType.CREDIT_CARD_NUMBER, EntityType.CREDIT_SCORE), true));

        assertTrue(prompt.contains("never a person's name"));
        assertTrue(prompt.contains("masked/last-four-only"));
        assertTrue(prompt.contains("300-850"));
        assertTrue(prompt.contains("policy/privacy language"));
        assertTrue(prompt.contains("examples are not"));
        assertTrue(prompt.contains("non-assignable"));
        assertTrue(prompt.contains("'Your credit score' column header"));
    }

    @Test
    void promptRespectsTenantSsnLastFourOption() {
        SensitiveContentPromptBuilder builder = new SensitiveContentPromptBuilder();
        String enabled = builder.build(new AnalysisPolicy(Set.of(EntityType.SSN), true));
        String disabled = builder.build(new AnalysisPolicy(Set.of(EntityType.SSN), false));

        assertTrue(enabled.contains("only the final four digits remain visible"));
        assertTrue(disabled.contains("Do NOT return last-four-only"));
    }

    @Test
    void tenantProfileRoutesIndependentlyFromDefaultProvider() {
        TenantId tenantA = TenantId.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        TenantId tenantB = TenantId.of(UUID.fromString("00000000-0000-0000-0000-0000000000b1"));
        AtomicInteger defaultCalls = new AtomicInteger();
        AtomicInteger tenantCalls = new AtomicInteger();

        SensitiveContentAnalysisPort defaultPort = request -> {
            defaultCalls.incrementAndGet();
            return new AnalysisOutput(List.of());
        };
        SensitiveContentAnalysisPort tenantPort = request -> {
            tenantCalls.incrementAndGet();
            return new AnalysisOutput(List.of());
        };

        TenantRoutingSensitiveContentAnalysisPort routing = new TenantRoutingSensitiveContentAnalysisPort(
                defaultPort, Map.of(tenantA, tenantPort));
        AnalysisPolicy policy = new AnalysisPolicy(Set.of(EntityType.CREDIT_SCORE), true);

        routing.analyze(new AnalysisRequest(tenantA, List.of(new PageContent(1, "FICO score: 720")), policy));
        routing.analyze(new AnalysisRequest(tenantB, List.of(new PageContent(1, "FICO score: 720")), policy));

        assertEquals(1, tenantCalls.get());
        assertEquals(1, defaultCalls.get());
    }
    @Test
    void explicitTenantProfileModeFailsClosedInsteadOfUsingDefaultProvider() {
        TenantId tenant = TenantId.of(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
        AtomicInteger defaultCalls = new AtomicInteger();
        SensitiveContentAnalysisPort defaultPort = request -> {
            defaultCalls.incrementAndGet();
            return new AnalysisOutput(List.of());
        };
        TenantRoutingSensitiveContentAnalysisPort routing =
                new TenantRoutingSensitiveContentAnalysisPort(defaultPort, Map.of(), true);

        assertThrows(RuntimeException.class, () -> routing.analyze(new AnalysisRequest(
                tenant, List.of(new PageContent(1, "FICO score: 720")),
                new AnalysisPolicy(Set.of(EntityType.CREDIT_SCORE), true))));
        assertEquals(0, defaultCalls.get(), "tenant content must not fall through to default AI provider");
    }

}
