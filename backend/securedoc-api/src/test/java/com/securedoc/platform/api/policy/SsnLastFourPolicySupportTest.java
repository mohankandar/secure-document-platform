package com.securedoc.platform.api.policy;

import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsnLastFourPolicySupportTest {

    private final LocalRuleBasedDetectionAdapter validator = new LocalRuleBasedDetectionAdapter();

    @Test
    void explicitSsnContextSupportsLastFourOnlyCandidate() {
        var evidence = validator.validate(new SensitiveDataValidationPort.Candidate(
                "SSN", "1234", "SSN: XXX-XX-1234"));

        assertEquals(SensitiveDataValidationPort.ValidationOutcome.SUPPORTS, evidence.outcome());
    }

    @Test
    void unrelatedFourDigitsAreNotValidatedAsSsn() {
        var evidence = validator.validate(new SensitiveDataValidationPort.Candidate(
                "SSN", "1234", "Room number: 1234"));

        assertEquals(SensitiveDataValidationPort.ValidationOutcome.CONTRADICTS, evidence.outcome());
    }

    @Test
    void defaultTenantPolicyMasksSsnLastFour() {
        var policy = TenantRedactionPolicy.defaults(TenantId.of(UUID.randomUUID()));

        assertTrue(policy.maskSsnLastFour());
    }
}
