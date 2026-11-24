package com.securedoc.install;

import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.LicensedSlots;
import com.securedoc.platform.api.context.RequestContextResolver.VerifiedSignIn;
import com.securedoc.platform.api.identity.EntitySlotService;
import com.securedoc.platform.api.identity.EntitySlotService.Answer;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.EntitySlotStore;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityReads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0058 step 1 on PostgreSQL: the Business Admin's entity slots, against the real idn_ tables. Throwaway server only. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class EntitySlotsPostgresTest {

    private static int count;
    private String n, audience, CUSTOMER, A, B, C, OTHER;
    private static final URI ISSUER = URI.create("https://login.slots.example/oauth2/default");
    private static final CorrelationId CORRELATION = CorrelationId.generate();

    private static ThrowawayDatabase db;
    private IdnRows rows;
    private EntitySlotStore store;
    private PlatformIdentityReads reads;
    private TransactionTemplate tx;
    private TenantId a, b;
    private final List<AuditRecord> audits = new ArrayList<>();
    private final Map<TenantId, TenantRedactionPolicy> policies = new HashMap<>();
    private LicensedSlots.Standing standing = LicensedSlots.Standing.IN_FORCE;
    private boolean licence = true;

    @BeforeAll
    static void create() throws SQLException {
        db = ThrowawayDatabase.create("slots");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
    }

    /** A customer, slots and login system of its own for each test, in one database. */
    @BeforeEach
    void setUp() throws SQLException {
        n = Integer.toString(1000 + ++count);
        audience = "api://slots-" + n;
        CUSTOMER = "TEN-SLOTS-" + n;
        A = "ENT-SLOTS-A" + n;
        B = "ENT-SLOTS-B" + n;
        C = "ENT-SLOTS-C" + n;
        OTHER = "ENT-SLOTS-O" + n;
        rows = new IdnRows(db);
        rows.customer(CUSTOMER);
        a = TenantId.of(rows.slot(CUSTOMER, A, "ACTIVE"));
        b = TenantId.of(rows.slot(CUSTOMER, B, "NOT_ACTIVATED"));
        rows.slot(CUSTOMER, C, "NOT_ACTIVATED");
        rows.lexaTenant(a.value(), "ACTIVE");
        rows.customer("TEN-OTHER-" + n);
        rows.slot("TEN-OTHER-" + n, OTHER, "NOT_ACTIVATED");
        rows.connection(CUSTOMER, "slots-okta", ISSUER, audience);
        rows.grants(A, ISSUER, new Grant("Admins A", "BUSINESS_ADMIN"), new Grant("Both", "BUSINESS_ADMIN"));
        rows.grants(B, ISSUER, new Grant("Both", "BUSINESS_ADMIN"), new Grant("Admins A", "DOCUMENT_PROCESSOR"));
        rows.grants(OTHER, ISSUER, new Grant("Both", "BUSINESS_ADMIN"));
        JdbcTemplate jdbc = new JdbcTemplate(rows.ds);
        store = new EntitySlotStore(jdbc);
        reads = new PlatformIdentityReads(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(rows.ds));
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) db.close();
    }

    private EntitySlotService service() {
        TenantRedactionPolicyRepository repository = new TenantRedactionPolicyRepository() {
            @Override
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) {
                return Optional.ofNullable(policies.get(tenantId));
            }

            @Override
            public void save(TenantRedactionPolicy policy) {
                policies.put(policy.tenantId(), policy);
            }
        };
        return new EntitySlotService(store, () -> licence
                ? Optional.of(new LicensedSlots.Current(CUSTOMER, Set.of(A, B), standing)) : Optional.empty(),
                repository, new AuditService(audits::add), Clock.systemUTC());
    }

    private static AuthenticatedPrincipal in(TenantId entity) {
        return AuthenticatedPrincipal.of(ActorId.of(UUID.fromString("00000000-0000-0000-0000-0000000000aa")), entity,
                "business.admin", Set.of("BUSINESS_ADMIN"));
    }

    private static Optional<VerifiedSignIn> groups(String... groups) {
        return Optional.of(new VerifiedSignIn(ISSUER, Set.of(groups)));
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private String one(String sql) {
        return new JdbcTemplate(rows.ds).queryForObject(sql, String.class);
    }

    @Test
    void aSlotIsActivatedNamedSuspendedAndResumedAndLexaVaultsOwnRowFollows() {
        EntitySlotService service = service();
        Answer done = inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, B, "  Northwind Trust  "));
        assertTrue(done.done(), done.toString());
        assertEquals("ACTIVATED", done.code());
        assertEquals("ACTIVE", one("select status from tenants where id = '" + b + "'"));
        assertEquals("Northwind Trust", one("select name from tenants where id = '" + b + "'"));
        assertEquals("ent-slots-b" + n, one("select slug from tenants where id = '" + b + "'"));
        assertEquals("7", one("select count(*)::text from tenant_capabilities where tenant_id = '" + b + "'"));
        assertTrue(policies.containsKey(b), "the default sensitive-data policy");
        assertEquals("00000000-0000-0000-0000-0000000000aa", one("select activated_by from idn_entity where slot_id = '" + B + "'"));
        assertTrue(store.active(b));
        assertEquals(Set.of(a, b), reads.findCandidates("slots-okta", ISSUER, audience));

        assertEquals("RENAMED", inTx(() -> service.rename(in(a), groups("Both"), CORRELATION, B, "Northwind Bank")).code());
        assertEquals("Northwind Bank", one("select name from tenants where id = '" + b + "'"));
        Answer taken = inTx(() -> service.rename(in(a), groups("Both"), CORRELATION, B, "entity ent-slots-a" + n));
        assertFalse(taken.done());
        assertEquals("NAME_TAKEN", taken.code());
        assertFalse(taken.whatToDo().isBlank());

        assertEquals("SUSPENDED", inTx(() -> service.suspend(in(a), groups("Both"), CORRELATION, B)).code());
        assertEquals("SUSPENDED", one("select status from tenants where id = '" + b + "'"));
        assertFalse(store.active(b), "no access and no intake");
        assertEquals(Set.of(a), reads.findCandidates("slots-okta", ISSUER, audience), "no sign-in");
        assertEquals("RESUMED", inTx(() -> service.resume(in(a), groups("Both"), CORRELATION, B)).code());
        assertTrue(store.active(b));

        // In the log of the entity the person is in, and of the entity concerned; codes only, never the name.
        List<AuditRecord> suspended = audits.stream().filter(r -> r.action() == AuditAction.ENTITY_SUSPENDED).toList();
        assertEquals(Set.of(a, b), Set.of(suspended.get(0).tenantId(), suspended.get(1).tenantId()));
        assertTrue(audits.stream().noneMatch(r -> r.detail().contains("Northwind")));
        assertTrue(audits.stream().allMatch(r -> r.detail().matches("code=[A-Z_]+")), audits.toString());
    }

    @Test
    void aSlotTheyDontManageIsNotShownNotChangedAndTheRefusalDoesNotSayWhetherItExists() {
        EntitySlotService service = service();
        // Admins A have the Business Admin role in A only (in B they are processors).
        assertEquals(List.of(A), inTx(() -> service.overview(in(a), groups("Admins A"))).entities().stream()
                .map(EntitySlotService.SlotView::slotId).toList());
        assertEquals(List.of(A, B), inTx(() -> service.overview(in(a), groups("Both"))).entities().stream()
                .map(EntitySlotService.SlotView::slotId).toList());
        List<String> messages = new ArrayList<>();
        for (String slot : List.of(B, C, OTHER, "ENT-NOWHERE", "x' or '1'='1")) {
            messages.add(assertThrows(AccessDeniedException.class, () -> inTx(() ->
                    service.activate(in(a), groups("Admins A"), CORRELATION, slot, "Taken over")), slot).getMessage());
        }
        assertEquals(1, Set.copyOf(messages).size());
        // Another customer's slot is out of reach even with a grant in it; another issuer's or differently written group gives nothing.
        assertThrows(AccessDeniedException.class, () -> inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, OTHER, "x")));
        assertThrows(AccessDeniedException.class, () -> inTx(() -> service.activate(in(a), groups("both"), CORRELATION, B, "x")));
        assertThrows(AccessDeniedException.class, () -> inTx(() -> service.activate(in(a), Optional.of(
                new VerifiedSignIn(URI.create("https://other.example"), Set.of("Both"))), CORRELATION, B, "x")));
        assertEquals("3", one("select count(*)::text from idn_entity where status = 'NOT_ACTIVATED' and slot_id like '%" + n + "'"));
        assertEquals(8, audits.stream().filter(r -> r.outcome() == AuditOutcome.DENIED && r.resourceId().equals("refused")).count());
    }

    @Test
    void activatingAndResumingFollowTheLicenceAndSuspendingIsAlwaysAllowed() {
        EntitySlotService service = service();
        licence = false;
        assertEquals("NO_LICENCE", inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, B, "B")).code());
        licence = true;
        standing = LicensedSlots.Standing.GRACE_OVER;
        assertEquals("LICENCE_EXPIRED", inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, B, "B")).code());
        assertEquals("NOT_ACTIVATED", one("select status from idn_entity where slot_id = '" + B + "'"));
        assertFalse(store.hasEntityRow(b.value()));
        standing = LicensedSlots.Standing.IN_FORCE;
        assertTrue(inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, B, "B")).done());
        licence = false;
        assertTrue(inTx(() -> service.suspend(in(a), groups("Both"), CORRELATION, B)).done());
        assertEquals("NO_LICENCE", inTx(() -> service.resume(in(a), groups("Both"), CORRELATION, B)).code());
        assertFalse(store.active(b));
    }

    @Test
    void theEntityAPersonIsInIsNotSuspendedByThem() {
        EntitySlotService service = service();
        Answer refused = inTx(() -> service.suspend(in(a), groups("Both"), CORRELATION, A));
        assertFalse(refused.done());
        assertEquals("ENTITY_IN_USE_HERE", refused.code());
        assertTrue(store.active(a));
        assertEquals(AuditOutcome.FAILURE, audits.get(audits.size() - 1).outcome());
    }

    /** Task 0058: two active entities behind one login system: each group decides its own entity, and nobody is locked out. */
    @Test
    void aSecondActiveEntityKeepsEveryoneSigningInToTheirOwn() {
        EntitySlotService service = service();
        assertTrue(inTx(() -> service.activate(in(a), groups("Both"), CORRELATION, B, "B")).done());
        var properties = new com.securedoc.platform.api.security.SecurityProperties();
        properties.getOidc().setProviderId("slots-okta");
        properties.getOidc().setAudience(audience);
        var signIn = new com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver(reads, reads, reads, properties);
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none").issuer(ISSUER.toString()).subject("s").build();
        assertEquals(Map.of(a, Set.of("BUSINESS_ADMIN"), b, Set.of("DOCUMENT_PROCESSOR")), signIn.authorizedTenants(jwt, Set.of("Admins A")));
        assertEquals(Map.of(a, Set.of("BUSINESS_ADMIN"), b, Set.of("BUSINESS_ADMIN")), signIn.authorizedTenants(jwt, Set.of("Both")));
        assertEquals(Map.of(), signIn.authorizedTenants(jwt, Set.of("Nobody")));
        assertTrue(inTx(() -> service.suspend(in(a), groups("Both"), CORRELATION, B)).done());
        assertEquals(Map.of(a, Set.of("BUSINESS_ADMIN")), signIn.authorizedTenants(jwt, Set.of("Both")), "a suspended entity lets nobody in");
    }

    @Test
    void aChangeThatFailsLaterChangesNothing() {
        EntitySlotService service = service();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            assertTrue(service.activate(in(a), groups("Both"), CORRELATION, B, "B").done());
            throw new IllegalStateException("the audit record could not be written");
        }));
        assertEquals("NOT_ACTIVATED", one("select status from idn_entity where slot_id = '" + B + "'"));
        assertFalse(store.hasEntityRow(b.value()));
    }

    @Test
    void anEntityIsActiveOnlyWhenItsOwnRowAndItsSlotAre() throws SQLException {
        assertTrue(store.active(a));
        rows.exec("update idn_entity set status = 'NOT_IN_LICENCE' where slot_id = ?", A);
        assertFalse(store.active(a));
        rows.exec("update idn_entity set status = 'ACTIVE' where slot_id = ?", A);
        rows.exec("update tenants set status = 'SUSPENDED' where id = ?", a.value());
        assertFalse(store.active(a));
        assertFalse(store.active(TenantId.of(UUID.randomUUID())));
        UUID old = UUID.randomUUID();
        rows.lexaTenant(old, "ACTIVE");
        assertTrue(store.active(TenantId.of(old)), "an entity made before the slots: its own row decides");
    }

    @Test
    void theEntitysTimeZoneIsItsOwnElseTheCustomers() throws SQLException {
        EntitySlotService service = service();
        assertTrue(store.effective(a).isEmpty());
        rows.exec("update idn_customer set time_zone = 'America/Chicago' where tenant_id = ?", CUSTOMER);
        assertEquals(ZoneId.of("America/Chicago"), store.effective(a).orElseThrow());
        assertEquals("SET", inTx(() -> service.setTimeZone(in(a), groups("Both"), CORRELATION, A, "Europe/London")).code());
        assertEquals(ZoneId.of("Europe/London"), store.effective(a).orElseThrow());
        Answer invalid = inTx(() -> service.setTimeZone(in(a), groups("Both"), CORRELATION, A, "+05:00"));
        assertEquals("TIME_ZONE_INVALID", invalid.code());
        assertEquals("code=TIME_ZONE_INVALID", audits.get(audits.size() - 1).detail(), "what was typed is not recorded");
        assertEquals("CLEARED", inTx(() -> service.setTimeZone(in(a), groups("Both"), CORRELATION, A, " ")).code());
        assertEquals(ZoneId.of("America/Chicago"), store.effective(a).orElseThrow());
        assertEquals("code=CLEARED, timeZone=America/Chicago", audits.get(audits.size() - 1).detail());
        assertTrue(store.effective(TenantId.of(UUID.randomUUID())).isEmpty());
        assertNull(inTx(() -> service.overview(in(a), groups("Both"))).entities().get(0).timeZone());
        // A day is not always 24 hours long in it, which is why a name and not an offset is kept.
        ZonedDateTime spring = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, store.effective(a).orElseThrow());
        assertEquals(Duration.ofHours(23), Duration.between(spring, spring.plusDays(1)));
        assertEquals(Instant.parse("2026-03-08T06:00:00Z"), spring.toInstant());
    }
}
