package com.securedoc.platform.domain;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0058: a retention timer is a number of hours from a fixed moment. The moment is worked out once, at the export, so no
 * time zone, no change of one and no day on which the clocks change makes a copy go earlier (or later).
 */
class RetentionTimersTest {

    @Test
    void theHoursAreElapsedHoursInEveryTimeZoneAndOnTheDaysTheClocksChange() {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
                for (String exported : new String[] {"2026-03-08T04:30:00Z", "2026-11-01T03:30:00Z", "2026-07-01T12:00:00Z"}) {
                    Instant at = Instant.parse(exported);
                    TenantId tenant = TenantId.generate();
                    TenantLifecyclePolicy policy = TenantLifecyclePolicy.defaults(tenant);
                    PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
                    life.recordSuccessfulExport(1, at, policy);
                    assertEquals(Duration.ofHours(policy.originalRetentionHours()), Duration.between(at, life.originalDeleteAt().orElseThrow()), zone);
                    assertEquals(Duration.ofHours(policy.redactedCacheHours()), Duration.between(at, life.redactedDeleteAt().orElseThrow()), zone);
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
