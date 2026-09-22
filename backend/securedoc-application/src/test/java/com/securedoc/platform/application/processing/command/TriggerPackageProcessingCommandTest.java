package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerPackageProcessingCommandTest {

    @Test
    void ordinaryContinuationPreservesExistingAttemptBudget() {
        var command = TriggerPackageProcessingCommand.of(
                PackageId.generate(), TenantId.generate(), ActorId.generate(), CorrelationId.generate());

        assertFalse(command.recovery());
        assertFalse(command.resetAttemptBudget(),
                "review/final-approval continuations must not reset a recovered package back to attempt 1");
    }

    @Test
    void explicitRedriveStartsFreshAttemptBudget() {
        var command = TriggerPackageProcessingCommand.freshCycle(
                PackageId.generate(), TenantId.generate(), ActorId.generate(), CorrelationId.generate());

        assertFalse(command.recovery());
        assertTrue(command.resetAttemptBudget());
    }

    @Test
    void automaticRecoveryIncrementsWithoutResettingBudget() {
        var command = TriggerPackageProcessingCommand.recovery(
                PackageId.generate(), TenantId.generate(), ActorId.generate(), CorrelationId.generate());

        assertTrue(command.recovery());
        assertFalse(command.resetAttemptBudget());
    }
}
