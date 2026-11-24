package com.securedoc.platform.api.licensing;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.ClockReading;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.LicenceType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Task 0057: licence states for tests (no signing: the states are what platform-licensing makes of a date). */
public final class TestLicence {

    public static final String SLOT = "ENT-DEMO-0001";

    private TestLicence() {
    }

    /** A subscription from 2026-01-01 to {@code expiresOn}, read at {@code now}. */
    public static LicenceStatus status(LicenceKind kind, LocalDate expiresOn, Instant now, String... products) {
        List<String> listed = products.length == 0 ? List.of("LEXAVAULT") : List.of(products);
        Licence licence = new Licence("LIC-TEST-0001", 1, "Test Customer", "TEN-TEST-0001", listed,
                List.of(new EntitySlot(SLOT, List.of(new Allowance(listed.get(0), AllowanceType.PAGES_PER_MONTH, 100)))),
                LicenceType.SUBSCRIPTION, kind, LocalDate.parse("2026-01-01"), expiresOn, null, Deployment.ON_PREM,
                "test-key-1", Instant.parse("2025-12-01T00:00:00Z"));
        return LicenceStatus.at(licence, new ClockReading(now, null, now, Duration.ofHours(1)), null);
    }

    /** Read on {@code day} at noon UTC. */
    public static LicenceStatus on(String day, LicenceKind kind) {
        return status(kind, LocalDate.parse("2026-06-30"),
                LocalDate.parse(day).atTime(12, 0).toInstant(ZoneOffset.UTC));
    }
}
