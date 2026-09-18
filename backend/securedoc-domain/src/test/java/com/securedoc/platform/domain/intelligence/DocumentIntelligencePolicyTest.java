package com.securedoc.platform.domain.intelligence;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIntelligencePolicyTest {

    @Test
    void defaultIsDisabledAndPinnedToFrozenR11Baseline() {
        TenantDocumentIntelligencePolicy policy = TenantDocumentIntelligencePolicy.defaults(TenantId.generate(), Instant.now());
        assertEquals(DocumentIntelligenceMode.DISABLED, policy.mode());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, policy.activeBundleVersion());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, policy.lastKnownGoodBundleVersion());
        assertEquals(1, policy.configurationVersion());
    }

    @Test
    void modeSemanticsKeepObserveNonInfluentialAndEnabledBaselineSafe() {
        assertFalse(DocumentIntelligenceMode.DISABLED.executionAllowed());
        assertFalse(DocumentIntelligenceMode.DISABLED.decisionInfluenceAllowed());
        assertTrue(DocumentIntelligenceMode.OBSERVE.executionAllowed());
        assertFalse(DocumentIntelligenceMode.OBSERVE.decisionInfluenceAllowed());
        assertTrue(DocumentIntelligenceMode.ENABLED.executionAllowed());
        assertTrue(DocumentIntelligenceMode.ENABLED.decisionInfluenceAllowed());
    }

    @Test
    void disabledSnapshotAlwaysUsesFrozenBaselineEvenWhenAnotherBundleIsPinned() {
        TenantId tenant = TenantId.generate();
        TenantDocumentIntelligencePolicy policy = TenantDocumentIntelligencePolicy.defaults(tenant, Instant.now())
                .reconfigure(DocumentIntelligenceMode.DISABLED, "profile-v2", Instant.now());
        PackageProcessingIntelligenceSnapshot snapshot = PackageProcessingIntelligenceSnapshot.capture(
                tenant, PackageId.generate(), 1, policy, true, Instant.now());

        assertEquals(DocumentIntelligenceMode.DISABLED, snapshot.effectiveMode());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, snapshot.bundleVersion());
        assertTrue(snapshot.baselineDiscoveryRequired());
        assertFalse(snapshot.intelligenceExecutionAllowed());
        assertFalse(snapshot.decisionInfluenceAllowed());
    }

    @Test
    void unavailableEnabledBundleFallsBackToFrozenBaselineWithoutInfluence() {
        TenantId tenant = TenantId.generate();
        TenantDocumentIntelligencePolicy policy = TenantDocumentIntelligencePolicy.defaults(tenant, Instant.now())
                .reconfigure(DocumentIntelligenceMode.ENABLED, "missing-v2", Instant.now());
        PackageProcessingIntelligenceSnapshot snapshot = PackageProcessingIntelligenceSnapshot.capture(
                tenant, PackageId.generate(), 1, policy, false, Instant.now());

        assertEquals(DocumentIntelligenceMode.ENABLED, snapshot.requestedMode());
        assertEquals(DocumentIntelligenceMode.DISABLED, snapshot.effectiveMode());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, snapshot.bundleVersion());
        assertTrue(snapshot.fallbackToBaseline());
        assertTrue(snapshot.baselineDiscoveryRequired());
        assertFalse(snapshot.decisionInfluenceAllowed());
    }
}
