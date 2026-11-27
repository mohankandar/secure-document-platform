package com.securedoc.platform.application.integration;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationConnectionServiceTest {
    private final TenantId tenantA = TenantId.generate();
    private final TenantId tenantB = TenantId.generate();
    private final ActorId actor = ActorId.generate();
    private final CorrelationId correlation = CorrelationId.generate();
    private final FakeRepository repository = new FakeRepository();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final AuditPort auditPort = audits::add;
    private final IntegrationConnectionService service = new IntegrationConnectionService(repository,
            new AuditService(auditPort), Clock.fixed(Instant.parse("2026-09-02T20:00:00Z"), ZoneOffset.UTC))
            // Hardening: secret references limited to approved names; this test's own approved name (the app's rule is
            // tested in securedoc-api).
            .withSecretReferences((entity, reference) -> {
                if (!reference.equals("vault://tenant-a/title-secret")) throw new IllegalArgumentException("refused");
            });

    /** Hardening: secret references limited to approved names. */
    @Test
    void aSecretNameTheRuleRefusesIsNotSavedAndTheErrorNamesOnlyTheField() {
        var refused = assertThrows(ValidationException.class, () -> service.create(tenantA, actor, correlation,
                new IntegrationConnectionService.ConnectionInput("Title API", null, IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY,
                        IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null,
                        "https://title.example.test", null, "sys:user.home", false, 60, 2, 24, 50, 262144000L, 500,
                        IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true)));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.CFG_003, refused.lvCode());
        assertEquals(com.securedoc.platform.application.port.out.SecretReferenceRule.REFUSED, refused.getMessage());
        assertTrue(repository.findByTenantId(tenantA).isEmpty());
        assertTrue(audits.isEmpty());
    }

    @Test
    void tenantScopeIsAppliedToListAndGet() {
        var a = service.create(tenantA, actor, correlation, input("Source A"));
        service.create(tenantB, actor, correlation, input("Source B"));
        assertEquals(List.of(a), service.list(tenantA));
        assertThrows(NotFoundException.class, () -> service.get(tenantB, a.id()));
    }

    /** Task 0058: a daily schedule without a zone is taken only for an entity that has one; nothing is guessed. */
    @Test
    void aDailyScheduleWithoutAZoneNeedsAnEntityThatHasOne() {
        var daily = new IntegrationConnectionService.ConnectionInput("Daily source", null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null,
                "https://source.example.test", null, null, false, 60, 2, 24, 50, 262144000L, 500,
                com.securedoc.platform.domain.integration.IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), " ", 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true);
        var refused = assertThrows(ValidationException.class, () -> service.create(tenantA, actor, correlation, daily));
        assertEquals("Daily polling requires a time zone", refused.getMessage());
        service.withEntityTimeZones(entity -> entity.equals(tenantA) ? java.util.Optional.of(java.time.ZoneId.of("Europe/London")) : java.util.Optional.empty());
        assertNull(service.create(tenantA, actor, correlation, daily).pollingTimeZone(), "it follows the entity's, also when that changes");
        assertThrows(ValidationException.class, () -> service.create(tenantB, actor, correlation, daily));
    }

    @Test
    void duplicateNameWithinTenantIsRejectedCaseInsensitively() {
        service.create(tenantA, actor, correlation, input("Mortgage Source"));
        assertThrows(ValidationException.class, () -> service.create(tenantA, actor, correlation, input("mortgage source")));
        assertDoesNotThrow(() -> service.create(tenantB, actor, correlation, input("mortgage source")));
    }

    @Test
    void createUpdateAndDisableAreAuditedWithoutSecretValue() {
        var created = service.create(tenantA, actor, correlation, new IntegrationConnectionService.ConnectionInput(
                "Title API", null, IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://title.example.test", null,
                "vault://tenant-a/title-secret", false, 60, 2, 24, 50, 262144000L, 500,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true));
        var updated = service.update(tenantA, created.id(), actor, correlation,
                new IntegrationConnectionService.ConnectionInput("Title API", "Updated", IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY,
                        IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null,
                        "https://title2.example.test", null, null, true, 90, 3, 24, 50, 262144000L, 500,
                        IntegrationAcknowledgementMode.DESTINATION_RECEIPT, false));
        service.setEnabled(tenantA, updated.id(), false, actor, correlation);

        assertEquals(List.of(AuditAction.INTEGRATION_CONNECTION_CREATED, AuditAction.INTEGRATION_CONNECTION_UPDATED,
                        AuditAction.INTEGRATION_CONNECTION_DISABLED),
                audits.stream().map(AuditRecord::action).toList());
        assertTrue(audits.stream().allMatch(a -> a.detail() == null || !a.detail().contains("vault://")));
    }

    /** Task 0039: more than a case holds is refused on save, create or update, as LV-CFG-001 with the reason. */
    @Test
    void sourceLimitsAboveTheCaseLimitsAreRefusedAsLvCfg001() {
        ValidationException pages = assertThrows(ValidationException.class,
                () -> service.create(tenantA, actor, correlation, input("Too many pages", 50, 501)));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.CFG_001, pages.lvCode());
        assertEquals("Maximum PDF pages must be between 1 and 500: a case takes files of up to 500 pages", pages.getMessage());
        ValidationException files = assertThrows(ValidationException.class,
                () -> service.create(tenantA, actor, correlation, input("Too many files", 1000, 500)));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.CFG_001, files.lvCode());
        assertEquals("Maximum documents per intake must be between 1 and 50: a case holds up to 50 files", files.getMessage());

        var saved = service.create(tenantA, actor, correlation, input("At the limit", 50, 500));
        ValidationException update = assertThrows(ValidationException.class,
                () -> service.update(tenantA, saved.id(), actor, correlation, input("At the limit", 50, 2000)));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.CFG_001, update.lvCode());
        assertEquals(500, service.get(tenantA, saved.id()).maxPdfPages(), "nothing changed");
        // The draft test goes through the same checks, so a draft over the limit is refused too.
        assertThrows(ValidationException.class, () -> service.draft(tenantA, null, input("Draft", 51, 500)));
    }

    private IntegrationConnectionService.ConnectionInput input(String name) {
        return input(name, 50, 500);
    }

    private IntegrationConnectionService.ConnectionInput input(String name, int files, int pages) {
        return new IntegrationConnectionService.ConnectionInput(name, null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API, IntegrationAuthenticationType.NONE,
                null, null, null, null, false, 60, 2, 24, files, 262144000L, pages,
                IntegrationAcknowledgementMode.NONE, false);
    }

    private static final class FakeRepository implements IntegrationConnectionRepository {
        private final Map<UUID, IntegrationConnection> values = new LinkedHashMap<>();

        @Override public List<IntegrationConnection> findByTenantId(TenantId tenantId) {
            return values.values().stream().filter(v -> v.tenantId().equals(tenantId))
                    .sorted(Comparator.comparing(IntegrationConnection::name)).toList();
        }
        @Override public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) {
            return Optional.ofNullable(values.get(id.value())).filter(v -> v.tenantId().equals(tenantId));
        }
        @Override public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) {
            return values.values().stream().anyMatch(v -> v.tenantId().equals(tenantId)
                    && (excludeId == null || !v.id().equals(excludeId)) && v.name().equalsIgnoreCase(name));
        }
        @Override public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) {
            return values.values().stream().filter(v -> v.tenantId().equals(tenantId) && v.enabled()
                    && v.providerType() == IntegrationProviderType.PUSH_API
                    && clientPrincipal.equals(v.clientPrincipal())).findFirst();
        }
        @Override public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) {
            return values.values().stream().filter(v -> v.tenantId().equals(tenantId) && v.enabled()
                    && v.providerType() == IntegrationProviderType.SCANNER && clientPrincipal.equals(v.clientPrincipal())).findFirst();
        }
        @Override public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) {
            return values.values().stream().anyMatch(v -> v.tenantId().equals(tenantId)
                    && (excludeId == null || !v.id().equals(excludeId))
                    && clientPrincipal.equals(v.clientPrincipal()));
        }
        @Override public void save(IntegrationConnection connection) { values.put(connection.id().value(), connection); }
    }
}
