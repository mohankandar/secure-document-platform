package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.mapping.MappingError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.infrastructure.identity.RoleMappingStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleMappingLoaderTest {

    static final UUID A = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b");
    static final UUID C = UUID.fromString("c3c3c3c3-0000-4000-8000-00000000000c");
    static final String ISSUER = "https://login.example.test/realms/shared";
    static final String HEADER = "# Format: lexavault-role-mapping/1\nentity_id,entity_name,group,role\n";

    /** In-memory tenants, ACTIVE login-system records and grants; counts every write. */
    static final class MemoryStore implements RoleMappingStore {
        final Map<UUID, Target> tenants = new HashMap<>();
        final Map<UUID, Set<Grant>> grants = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        final List<EntityId> locks = new ArrayList<>();

        @Override public Optional<Target> describe(EntityId entity) { return Optional.ofNullable(tenants.get(entity.value())); }
        @Override public void lock(EntityId tenant) { locks.add(tenant); }
        @Override public Optional<String> activeIssuer(EntityId tenant) {
            Target t = tenants.get(tenant.value());
            return t != null && t.activeLoginConnection() ? Optional.of(ISSUER) : Optional.empty();
        }
        @Override public Set<Grant> grants(EntityId tenant, String issuer) {
            return new LinkedHashSet<>(grants.getOrDefault(tenant.value(), Set.of()));
        }
        @Override public void add(EntityId tenant, String issuer, Grant grant) {
            writes.add("add " + tenant + " " + grant);
            grants.computeIfAbsent(tenant.value(), k -> new LinkedHashSet<>()).add(grant);
        }
        @Override public void remove(EntityId tenant, String issuer, Grant grant) {
            writes.add("remove " + tenant + " " + grant);
            grants.get(tenant.value()).remove(grant);
        }
    }

    final MemoryStore store = new MemoryStore();
    final List<AuditRecord> audit = new ArrayList<>();
    final RoleMappingLoader loader = new RoleMappingLoader(store, (AuditPort) audit::add, new ObjectMapper());

    RoleMappingLoaderTest() {
        store.tenants.put(A, new RoleMappingStore.Target("Acme Lending", true, true));
        store.tenants.put(B, new RoleMappingStore.Target("Acme Servicing", true, true));
        store.tenants.put(C, new RoleMappingStore.Target("Untouched Co", true, true));
        store.grants.put(C, new LinkedHashSet<>(Set.of(new Grant("c-staff", "DOCUMENT_PROCESSOR"))));
    }

    private RoleMappingLoader.Outcome load(String rows, boolean dryRun) {
        return loader.load((HEADER + rows).getBytes(StandardCharsets.UTF_8), "acme.csv", "installer", dryRun);
    }

    private static final String FILE = A + ",Acme Lending,Lending-Processors,DOCUMENT_PROCESSOR\n"
            + A + ",Acme Lending,Acme-IT,TENANT_IT_ADMIN\n"
            + B + ",Acme Servicing,Servicing-Staff,DOCUMENT_SUPERVISOR\n";

    @Test
    void aFirstLoadWritesTheGrantsAndOneAuditRecordPerChangedEntity() throws Exception {
        var outcome = load(FILE, false);
        assertTrue(outcome.written());
        assertEquals(Set.of(new Grant("Lending-Processors", "DOCUMENT_PROCESSOR"), new Grant("Acme-IT", "TENANT_IT_ADMIN")), store.grants.get(A));
        assertEquals(Set.of(new Grant("Servicing-Staff", "DOCUMENT_SUPERVISOR")), store.grants.get(B));
        assertEquals(2, audit.size());
        AuditRecord first = audit.get(0);
        assertEquals(AuditAction.ROLE_MAPPING_LOADED, first.action());
        assertEquals(A, first.tenantId().value());
        assertEquals(RoleMappingLoader.SYSTEM_ACTOR, first.actorId());
        Map<?, ?> detail = new ObjectMapper().readValue(first.detail(), Map.class);
        assertEquals(2, detail.get("added"));
        assertEquals(0, detail.get("removed"));
        assertEquals(outcome.sha256(), detail.get("fileSha256"));
        assertEquals("acme.csv", detail.get("fileName"));
        assertEquals("installer", detail.get("osUser"));
        assertFalse(first.detail().contains("Lending-Processors"), "group names are not repeated in the audit trail");
        assertEquals(64, outcome.sha256().length());
    }

    @Test
    void theSameFileTwiceWritesNothingAndRecordsNothing() {
        load(FILE, false);
        int writes = store.writes.size();
        int records = audit.size();
        var second = load(FILE, false);
        assertFalse(second.written());
        assertFalse(second.plan().hasChanges());
        assertEquals(writes, store.writes.size());
        assertEquals(records, audit.size());
    }

    @Test
    void theFileReplacesTheNamedEntitysGrantsAndLeavesOthersAlone() {
        load(FILE, false);
        audit.clear();
        var outcome = load(A + ",Acme Lending,Lending-Processors,DOCUMENT_PROCESSOR\n", false);
        assertTrue(outcome.written());
        assertEquals(Set.of(new Grant("Lending-Processors", "DOCUMENT_PROCESSOR")), store.grants.get(A));
        assertEquals(Set.of(new Grant("Servicing-Staff", "DOCUMENT_SUPERVISOR")), store.grants.get(B), "B is not in the file");
        assertEquals(Set.of(new Grant("c-staff", "DOCUMENT_PROCESSOR")), store.grants.get(C), "C is not in the file");
        assertEquals(1, audit.size());
        assertTrue(audit.get(0).detail().contains("\"removed\":1"));
    }

    @Test
    void aDryRunReportsTheChangesAndWritesNothing() {
        var outcome = load(FILE, true);
        assertTrue(outcome.plan().hasChanges());
        assertFalse(outcome.written());
        assertTrue(store.writes.isEmpty());
        assertTrue(audit.isEmpty());
    }

    @Test
    void aRefusedFileWritesNothingAndNamesTheLine() {
        var outcome = load(FILE + A + ",Acme Lending,Lending-Admins,BUSINES_ADMIN\n", false);
        assertTrue(outcome.refused());
        assertEquals(List.of("line 6: unknown role \"BUSINES_ADMIN\""), outcome.errors().stream().map(MappingError::display).toList());
        assertTrue(store.writes.isEmpty());
        assertTrue(audit.isEmpty());
        assertTrue(store.locks.isEmpty(), "a file refused on reading takes no locks");
    }

    @Test
    void anotherFormatIsRefused() {
        var outcome = loader.load(("# Format: lexavault-role-mapping/2\nentity_id,entity_name,group,role\n" + FILE)
                .getBytes(StandardCharsets.UTF_8), "acme.csv", "installer", false);
        assertEquals(MappingError.Code.UNSUPPORTED_FORMAT, outcome.errors().get(0).code());
    }

    @Test
    void lexaVaultRolesAreWrittenExactlyAndAliasesAreRefused() {
        assertEquals(MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY,
                load(A + ",Acme Lending,g,USER\n", false).errors().get(0).code());
        assertEquals(MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY,
                load(A + ",Acme Lending,g,document_processor\n", false).errors().get(0).code());
        assertEquals(MappingError.Code.PLATFORM_ROLE,
                load(A + ",Acme Lending,g,PLATFORM_OPERATIONS_ADMIN\n", false).errors().get(0).code());
    }

    @Test
    void entitiesAreLockedInAFixedOrder() {
        load(B + ",Acme Servicing,s,DOCUMENT_SUPERVISOR\n" + A + ",Acme Lending,l,DOCUMENT_PROCESSOR\n", false);
        assertEquals(List.of(EntityId.of(A), EntityId.of(B)), store.locks);
    }
}
