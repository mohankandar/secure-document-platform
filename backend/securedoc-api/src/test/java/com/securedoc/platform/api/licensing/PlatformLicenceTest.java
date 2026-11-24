package com.securedoc.platform.api.licensing;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceRefusal;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import com.documentplatform.notifications.EventCatalogue;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0057: intake on the licence's word, fail closed. */
class PlatformLicenceTest {

    private static final TenantId ENTITY = TenantId.of(EntityUuid.fromSlotId(TestLicence.SLOT));
    private final List<AuditRecord> audits = new ArrayList<>();
    private final AtomicLong nanos = new AtomicLong();

    private PlatformLicence licence(Supplier<LicenceStatus> read) {
        return new PlatformLicence(read, s -> s.licence().products().contains("LEXAVAULT"), new AuditService(audits::add),
                nanos::get);
    }

    private PlatformLicence on(String day) {
        return licence(() -> TestLicence.on(day, LicenceKind.PRODUCTION));
    }

    @Test
    void intakeGoesOnUntilTheEndOfGraceAndStopsTheDayAfter() {
        for (String allowed : List.of("2026-01-01", "2026-06-30", "2026-07-01", "2026-07-30")) {
            assertTrue(on(allowed).refusal(ENTITY).isEmpty(), allowed);
        }
        LicenseEnforcementException over = on("2026-07-31").refusal(ENTITY).orElseThrow();
        assertEquals("LICENSE_EXPIRED", over.errorCode());
        assertEquals("EXPIRED_GRACE_OVER", over.platformCode().orElseThrow());
        assertEquals(LvErrorCode.LIC_002, LvErrorCodes.of(over));
        LicenseEnforcementException early = on("2025-12-31").refusal(ENTITY).orElseThrow();
        assertEquals("LICENSE_NOT_YET_VALID", early.errorCode());
        assertEquals(LvErrorCode.LIC_002, LvErrorCodes.of(early));
    }

    @Test
    void aRefusedLicenceStopsIntakeWithThePlatformsOwnCode() {
        for (LicenceRefusal code : LicenceRefusal.values()) {
            PlatformLicence licence = licence(() -> { throw new LicenceException(code); });
            LicenseEnforcementException refused = licence.refusal(ENTITY).orElseThrow();
            assertEquals(code.name(), refused.platformCode().orElseThrow());
            assertEquals(code == LicenceRefusal.LICENCE_MISSING ? LvErrorCode.LIC_001 : LvErrorCode.LIC_003,
                    LvErrorCodes.of(refused), code.name());
            assertTrue(licence.customerName().isEmpty());
        }
    }

    @Test
    void anotherProductOrAnEntityNotInTheLicenceIsRefused() {
        PlatformLicence other = licence(() -> TestLicence.status(LicenceKind.PRODUCTION, LocalDate.parse("2026-06-30"),
                Instant.parse("2026-03-01T00:00:00Z"), "OTHER_PRODUCT"));
        assertEquals(LvErrorCode.LIC_004, LvErrorCodes.of(other.refusal(ENTITY).orElseThrow()));
        LicenseEnforcementException entity = on("2026-03-01").refusal(TenantId.generate()).orElseThrow();
        assertEquals("ENTITY_NOT_IN_LICENCE", entity.platformCode().orElseThrow());
        assertEquals(LvErrorCode.LIC_003, LvErrorCodes.of(entity));
    }

    @Test
    void whileTheDatabaseIsAwayTheLastReadingCountsForTwoMinutesOnly() {
        AtomicReference<RuntimeException> broken = new AtomicReference<>();
        PlatformLicence licence = licence(() -> {
            if (broken.get() != null) throw broken.get();
            return TestLicence.on("2026-03-01", LicenceKind.PRODUCTION);
        });
        broken.set(new IllegalStateException("database away"));
        nanos.set(PlatformLicence.MAX_AGE.toNanos());
        licence.read();
        assertTrue(licence.refusal(ENTITY).isEmpty());
        nanos.incrementAndGet();
        LicenseEnforcementException refused = licence.refusal(ENTITY).orElseThrow();
        assertEquals("LICENSE_CONFIGURATION_ERROR", refused.errorCode());
        assertTrue(refused.serviceUnavailable());
        broken.set(null);
        licence.read();
        assertTrue(licence.refusal(ENTITY).isEmpty(), "readable again");
    }

    @Test
    void aRefusalIsAuditedOnceWithCodesOnly() {
        assertThrows(LicenseEnforcementException.class, () -> on("2026-09-01").requireIntake(ENTITY, ActorId.generate(),
                CorrelationId.generate()));
        assertEquals(1, audits.size());
        assertEquals(AuditAction.LICENSE_ENFORCEMENT_DENIED, audits.get(0).action());
        on("2026-03-01").requireIntake(ENTITY, ActorId.generate(), CorrelationId.generate());
        assertEquals(1, audits.size());
    }

    @Test
    void onlyATestLicenceMarksOutputs() {
        for (LicenceKind kind : LicenceKind.values()) {
            assertEquals(kind == LicenceKind.NON_PRODUCTION,
                    licence(() -> TestLicence.on("2026-03-01", kind)).testEnvironment(), kind.name());
        }
        PlatformLicence never = licence(() -> { throw new LicenceException(LicenceRefusal.LICENCE_MISSING); });
        assertThrows(LicenseEnforcementException.class, never::testEnvironment);
        AtomicReference<RuntimeException> broken = new AtomicReference<>();
        PlatformLicence known = licence(() -> {
            if (broken.get() != null) throw broken.get();
            return TestLicence.on("2026-03-01", LicenceKind.NON_PRODUCTION);
        });
        broken.set(new LicenceException(LicenceRefusal.LICENCE_TAMPERED));
        known.read();
        assertTrue(known.testEnvironment(), "the last licence that could be read");
        assertTrue(known.refusal(ENTITY).isPresent(), "while intake is refused");
    }

    @Test
    void theCustomerIsTheLicences() {
        assertEquals("Test Customer", on("2026-03-01").customerName().orElseThrow());
    }

    @Test
    void theLicensingEventsAreRegisteredForTheAdmins() {
        EventCatalogue catalogue = new PlatformLicensingConfig().lexaVaultEventCatalogue();
        LicensingEvents.ALL.forEach(id -> assertTrue(catalogue.find(id).isPresent(), id.value()));
        assertEquals("TENANT_IT_ADMIN", catalogue.itAdminRole().value());
    }
}
