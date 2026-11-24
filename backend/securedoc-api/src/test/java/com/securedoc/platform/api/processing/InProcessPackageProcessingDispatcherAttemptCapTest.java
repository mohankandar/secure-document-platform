package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0023 C1 (#346, #358): failed dispatches of one case are capped; a licence denial stops at once. */
class InProcessPackageProcessingDispatcherAttemptCapTest {

    @Test
    void caseIsStoppedAfterThreeFailedDispatchesAndNotBefore() {
        AtomicInteger executions = new AtomicInteger();
        List<Integer> stopAttempts = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean();
        InProcessPackageProcessingDispatcher dispatcher = dispatcher(
                command -> {
                    executions.incrementAndGet();
                    throw new NotFoundException("DocumentPackage", command.packageId().toString());
                },
                (command, failure, failedAttempts, maxAttempts) -> {
                    stopAttempts.add(failedAttempts);
                    stopped.set(true);
                    return true;
                });

        // The recovery scan redispatches a SUBMITTED case while it is not stopped.
        for (int scan = 0; scan < 10 && !stopped.get(); scan++) {
            dispatcher.dispatch(command());
        }

        assertEquals(3, executions.get(), "The case must be tried exactly 3 times");
        assertEquals(List.of(3), stopAttempts, "The stop must be requested once, after the third failure");
    }

    @Test
    void licenceDenialStopsAtOnceSoOnlyOneDenialIsRecorded() {
        AtomicInteger denials = new AtomicInteger();
        List<Integer> stopAttempts = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean();
        InProcessPackageProcessingDispatcher dispatcher = dispatcher(
                command -> {
                    // IntakeLicence.requireIntake writes one LICENSE_ENFORCEMENT_DENIED
                    // audit row per call before it throws; count the calls.
                    denials.incrementAndGet();
                    throw new LicenseEnforcementException("LICENSE_MISSING", "denied", LicenseValidationStatus.MISSING);
                },
                (command, failure, failedAttempts, maxAttempts) -> {
                    stopAttempts.add(failedAttempts);
                    stopped.set(true);
                    return true;
                });

        for (int scan = 0; scan < 10 && !stopped.get(); scan++) {
            dispatcher.dispatch(command());
        }

        assertEquals(1, denials.get(), "A licence denial must not be retried, so it is audited once");
        assertEquals(List.of(1), stopAttempts);
    }

    @Test
    void licenceConfigurationErrorIsCountedLikeAnyOtherFailure() {
        AtomicInteger executions = new AtomicInteger();
        List<Integer> stopAttempts = new ArrayList<>();
        InProcessPackageProcessingDispatcher dispatcher = dispatcher(
                command -> {
                    executions.incrementAndGet();
                    throw new LicenseEnforcementException("LICENSE_CONFIGURATION_ERROR", "broken",
                            LicenseValidationStatus.CONFIGURATION_ERROR);
                },
                (command, failure, failedAttempts, maxAttempts) -> {
                    stopAttempts.add(failedAttempts);
                    return true;
                });

        dispatcher.dispatch(command());
        dispatcher.dispatch(command());
        assertTrue(stopAttempts.isEmpty(), "A broken licence service is not a denial; it is retried");
        dispatcher.dispatch(command());
        assertEquals(List.of(3), stopAttempts);
    }

    @Test
    void successfulDispatchResetsTheCount() {
        AtomicInteger executions = new AtomicInteger();
        List<Integer> stopAttempts = new ArrayList<>();
        InProcessPackageProcessingDispatcher dispatcher = dispatcher(
                command -> {
                    int n = executions.incrementAndGet();
                    if (n != 3) throw new IllegalStateException("transient");
                },
                (command, failure, failedAttempts, maxAttempts) -> {
                    stopAttempts.add(failedAttempts);
                    return true;
                });

        dispatcher.dispatch(command()); // fail 1
        dispatcher.dispatch(command()); // fail 2
        dispatcher.dispatch(command()); // success: count reset
        dispatcher.dispatch(command()); // fail 1
        dispatcher.dispatch(command()); // fail 2
        assertTrue(stopAttempts.isEmpty());
        dispatcher.dispatch(command()); // fail 3
        assertEquals(List.of(3), stopAttempts);
    }

    @Test
    void failingStopIsContainedAndTheLeaseIsStillReleased() {
        AtomicInteger releases = new AtomicInteger();
        PackageProcessingDispatchLease lease = new PackageProcessingDispatchLease() {
            @Override
            public java.util.Optional<Claim> tryClaim(TriggerPackageProcessingCommand command) {
                return java.util.Optional.of(new Claim(command.packageId(), command.tenantId(), "n", 1L, false));
            }

            @Override
            public void release(Claim claim) {
                releases.incrementAndGet();
            }
        };
        InProcessPackageProcessingDispatcher dispatcher = new InProcessPackageProcessingDispatcher(
                new SyncTaskExecutor(),
                command -> { throw new LicenseEnforcementException("LICENSE_MISSING", "denied", LicenseValidationStatus.MISSING); },
                lease, tenantId -> true,
                (command, failure, failedAttempts, maxAttempts) -> { throw new IllegalStateException("db down"); },
                3);

        dispatcher.dispatch(command());

        assertEquals(1, releases.get());
    }

    private static InProcessPackageProcessingDispatcher dispatcher(
            com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase useCase,
            InProcessPackageProcessingDispatcher.DispatchFailureStop stop) {
        return new InProcessPackageProcessingDispatcher(new SyncTaskExecutor(), useCase,
                PackageProcessingDispatchLease.disabled(), tenantId -> true, stop, 3);
    }

    private static TriggerPackageProcessingCommand command() {
        return TriggerPackageProcessingCommand.of(
                PackageId.of(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")),
                TenantId.of(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222")),
                ActorId.of(java.util.UUID.fromString("33333333-3333-3333-3333-333333333333")),
                CorrelationId.generate());
    }
}
