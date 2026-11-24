package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0049: the page batch settings of a policy: defaults, bounds, and what the audit says. */
class PageBatchSettingsSaveTest {

    @Test
    void twentyPagesAndTwoSharedByDefault() {
        assertEquals(new PageBatchSettings(20, 2), TenantRedactionPolicy.defaults(TenantId.generate()).pageBatches());
    }

    @Test
    void impossibleSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PageBatchSettings(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageBatchSettings(501, 2));
        assertThrows(IllegalArgumentException.class, () -> new PageBatchSettings(20, -1));
        assertThrows(IllegalArgumentException.class, () -> new PageBatchSettings(2, 2));
        assertThrows(IllegalArgumentException.class, () -> new PageBatchSettings(50, 21));
    }

    @Test
    void theAuditNamesThemOnlyWhenTheyAreNotTheDefault() {
        TenantRedactionPolicy standard = policy(PageBatchSettings.DEFAULTS);
        assertFalse(BusinessAdminConfigurationService.sensitiveDataPolicyChangeDetail(standard, null)
                .contains("pageBatch"));
        assertTrue(BusinessAdminConfigurationService.sensitiveDataPolicyChangeDetail(
                policy(new PageBatchSettings(30, 3)), standard).contains("pageBatchSize=30, pageBatchOverlap=3"));
    }

    private static TenantRedactionPolicy policy(PageBatchSettings batches) {
        return new TenantRedactionPolicy(TenantId.generate(), EnumSet.of(EntityType.SSN), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, batches);
    }
}
