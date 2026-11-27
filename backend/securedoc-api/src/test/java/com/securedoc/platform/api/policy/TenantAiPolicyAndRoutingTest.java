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

    /** Task 0072 (model change gate): without Person name the prompt is byte for byte the approved one. */
    @Test
    void thePromptsOfTheOtherKindsAreUnchangedAndNamesHaveTheirOwn() throws Exception {
        SensitiveContentPromptBuilder builder = new SensitiveContentPromptBuilder();
        AnalysisPolicy four = AnalysisPolicy.v1Defaults();
        // Task 0103: both prompts changed (every kind, no label needed); the prompt version is 1.4.0 and the gate is the benchmark.
        assertEquals("6b092f11af0bc8237c66fd661b91881ba97b3bdb6447367bef8bf3db716373b6", sha(builder.buildCompact(four)));
        assertEquals("863481dd96d0315ba44565602f9364c2983739b51878c3c58dccd670b053adbe", sha(builder.build(four)));
        assertEquals("5c1cc58ac9eaf55ed266eb80a7a6c84d49f5946e337b6fc4c2a77d1e5df9a4a3", sha(builder.buildNames()), "a change of the names prompt needs a new version and the gate");
        assertTrue((SensitiveContentPromptBuilder.NAMES_VERSION + ";validation=NOT_APPLICABLE;plausibility=PASS").length() <= 50,
                "findings.detector_version");
        java.util.Set<EntityType> five = java.util.EnumSet.copyOf(four.enabledEntityTypes());
        five.add(EntityType.PERSON_NAME);
        assertEquals(builder.buildCompact(four), builder.buildCompact(new AnalysisPolicy(five, true)));
    }

    private static String sha(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
