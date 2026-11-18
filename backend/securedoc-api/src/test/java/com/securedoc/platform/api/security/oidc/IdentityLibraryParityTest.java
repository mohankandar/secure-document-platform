package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.EntityGroupAuthorization;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.admission.EntitySelection;
import com.documentplatform.identity.admission.MachineEntityAdmission;
import com.documentplatform.identity.admission.SharedIssuerPreflight;
import com.documentplatform.identity.admission.TrustedEntityAdmission;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.refusingAsSecurityContext;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntities;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntity;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntityKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 1b step P1 parity: every case of the six R1B tests that move to Enterprise Identity 0.3.0, plus edge cases
 * and the shared-issuer request flow of RequestContextResolver, run through the OLD LexaVault classes and the NEW
 * library classes side by side. The library side goes through LexaVault's exception mapping, so a refusal is compared
 * as the SecurityContextException (HTTP 403) the product would raise, message included.
 *
 * If any case differs, the difference is the finding: neither side is adjusted to make this pass.
 */
class IdentityLibraryParityTest {

    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final TenantId DEMO = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    private static final URI ISSUER = URI.create("https://identity.example.test/realms/shared");
    private static final String PROVIDER = "customer-oidc";
    private static final String AUDIENCE = "lexavault-api";

    private static final ProductEntityRoleCatalog ROLES = new ProductEntityRoleCatalog();
    private static final List<String> RESULTS = new ArrayList<>();

    // ------------------------------------------------------------------ outcome comparison

    /** A normalised outcome: VALUE <canonical form>, REFUSED <message>, or <ExceptionClass> <message>. */
    private static String run(Supplier<Object> call) {
        try {
            return "VALUE " + canonical(call.get());
        } catch (SecurityContextException refused) {
            return "REFUSED " + refused.getMessage();
        } catch (RuntimeException other) {
            return other.getClass().getSimpleName() + " " + other.getMessage();
        }
    }

    private static String runLibrary(Supplier<Object> call) {
        return run(() -> refusingAsSecurityContext(call));
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof TenantId tenant) return tenant.value().toString();
        if (value instanceof EntityId entity) return entity.value().toString();
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, String> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(canonical(k), canonical(v)));
            return sorted.toString();
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            for (Object item : collection) items.add(canonical(item));
            items.sort(null);
            return items.toString();
        }
        return String.valueOf(value);
    }

    private static DynamicTest parity(String name, Supplier<Object> oldCall, Supplier<Object> newCall) {
        return DynamicTest.dynamicTest(name, () -> {
            String oldOutcome = run(oldCall);
            String newOutcome = runLibrary(newCall);
            RESULTS.add((oldOutcome.equals(newOutcome) ? "SAME " : "DIFF ") + name + " | old=" + oldOutcome + " | new=" + newOutcome);
            assertEquals(oldOutcome, newOutcome, "parity difference in case: " + name);
        });
    }

    @AfterAll
    static void printResults() {
        long same = RESULTS.stream().filter(r -> r.startsWith("SAME")).count();
        System.out.println("IDENTITY_LIBRARY_PARITY cases=" + RESULTS.size() + " same=" + same + " different=" + (RESULTS.size() - same));
        RESULTS.stream().filter(r -> r.startsWith("DIFF")).forEach(r -> System.out.println("IDENTITY_LIBRARY_PARITY " + r));
    }

    private static Set<TenantId> set(TenantId... tenants) {
        return Set.of(tenants);
    }

    private static Set<TenantId> setWithNull() {
        return new HashSet<>(Arrays.asList((TenantId) null));
    }

    // ------------------------------------------------------------------ 1. TrustedTenantAdmission (TrustedTenantAdmissionTest + edges)

    @TestFactory
    Stream<DynamicTest> trustedTenantAdmission() {
        TrustedTenantAdmission old = new TrustedTenantAdmission();
        TrustedEntityAdmission lib = new TrustedEntityAdmission();
        List<DynamicTest> cases = new ArrayList<>();
        Object[][] authorized = {
                {"deniesUntrustedTenant: A in {A}", A, set(A)},
                {"deniesUntrustedTenant: B in {A}", B, set(A)},
                {"deniesUntrustedTenant: A in {}", A, set()},
                {"ambiguousCandidatesFailClosed: B in {A,B}", B, set(A, B)},
                {"edge: A in null", A, null},
                {"edge: null requested", null, set(A)},
        };
        for (Object[] c : authorized) {
            TenantId requested = (TenantId) c[1];
            @SuppressWarnings("unchecked") Set<TenantId> members = (Set<TenantId>) c[2];
            cases.add(parity("admission.requireAuthorized " + c[0],
                    () -> old.requireAuthorized(requested, members),
                    () -> lib.requireAuthorized(toEntity(requested), toEntities(members))));
        }
        Object[][] unambiguous = {
                {"ambiguousCandidatesFailClosed: {A,B}", set(A, B)},
                {"edge: {A}", set(A)},
                {"edge: {}", set()},
                {"edge: null", null},
        };
        for (Object[] c : unambiguous) {
            @SuppressWarnings("unchecked") Set<TenantId> members = (Set<TenantId>) c[1];
            cases.add(parity("admission.requireUnambiguous " + c[0],
                    () -> old.requireUnambiguous(members),
                    () -> lib.requireUnambiguous(toEntities(members))));
        }
        return cases.stream();
    }

    // ------------------------------------------------------------------ 2. OidcTenantSelection (OidcTenantSelectionTest + edges)

    @TestFactory
    Stream<DynamicTest> tenantSelection() {
        OidcTenantSelection old = new OidcTenantSelection();
        EntitySelection lib = new EntitySelection();
        Map<TenantId, Set<String>> onlyA = Map.of(A, Set.of("DOCUMENT_PROCESSOR"));
        Map<TenantId, Set<String>> both = Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN"));
        Object[][] cases = {
                {"singleAuthorizedTenantDoesNotRequireSelection", null, onlyA},
                {"multipleMembershipsRequireExplicitChoice: no choice", null, both},
                {"multipleMembershipsRequireExplicitChoice: choose B", B.value().toString(), both},
                {"fabricatedOrMalformedTenantNeverGrantsAccess: B not a member", B.value().toString(), onlyA},
                {"fabricatedOrMalformedTenantNeverGrantsAccess: malformed", "not-a-uuid", onlyA},
                {"fabricatedOrMalformedTenantNeverGrantsAccess: no memberships", null, Map.of()},
                {"edge: blank choice", "  ", onlyA},
                {"edge: padded choice", " " + A.value() + " ", onlyA},
                {"edge: upper-case choice", A.value().toString().toUpperCase(), onlyA},
                {"edge: null memberships, no choice", null, null},
                {"edge: null memberships, choice", A.value().toString(), null},
                {"edge: almost-uuid", "00000000-0000-0000-0000-00000000000Z", onlyA},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : cases) {
            String header = (String) c[1];
            @SuppressWarnings("unchecked") Map<TenantId, Set<String>> memberships = (Map<TenantId, Set<String>>) c[2];
            tests.add(parity("selection " + c[0],
                    () -> old.select(header, memberships),
                    () -> lib.select(header, toEntityKeys(memberships))));
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ 3. TenantGroupAuthorization (TenantGroupAuthorizationTest + edges)

    @TestFactory
    Stream<DynamicTest> groupAuthorization() {
        TenantGroupAuthorization old = new TenantGroupAuthorization();
        EntityGroupAuthorization lib = new EntityGroupAuthorization(ROLES);
        Map<TenantId, Map<String, Set<String>>> scoped = Map.of(
                A, Map.of("tenant-a-processors", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b-admins", Set.of("TENANT_IT_ADMIN")));
        Map<TenantId, Map<String, Set<String>>> platform = Map.of(A, Map.of("operations-group", Set.of("PLATFORM_OPERATIONS_ADMIN")));
        Map<TenantId, Map<String, Set<String>>> onlyA = Map.of(A, Map.of("a-group", Set.of("DOCUMENT_PROCESSOR")));
        Map<TenantId, Map<String, Set<String>>> aliases = Map.of(A, Map.of("g", Set.of("USER", "approver", " Reviewer ")));
        Map<TenantId, Map<String, Set<String>>> bogus = Map.of(A, Map.of("g", Set.of("BOGUS")));
        Map<TenantId, Map<String, Set<String>>> emptyGrants = Map.of(A, Map.of());
        List<Object> mixedGroups = new ArrayList<>(Arrays.asList(" tenant-a-processors ", null, 42, " "));
        Object[][] roles = {
                {"rolesAreScopedToSelectedTenant: A with both groups", A, Set.of("tenant-a-processors", "tenant-b-admins"), scoped},
                {"rolesAreScopedToSelectedTenant: B with both groups", B, Set.of("tenant-a-processors", "tenant-b-admins"), scoped},
                {"rolesAreScopedToSelectedTenant: B with A's group", B, Set.of("tenant-a-processors"), scoped},
                {"tenantGroupCannotGrantPlatformOperations", A, Set.of("operations-group"), platform},
                {"rejectsMissingTenantPolicyAndAbsentGroups: B has no policy", B, Set.of("a-group"), onlyA},
                {"rejectsMissingTenantPolicyAndAbsentGroups: null groups", A, null, onlyA},
                {"rejectsMissingTenantPolicyAndAbsentGroups: no groups", A, Set.of(), onlyA},
                {"edge: legacy aliases and case", A, Set.of("g"), aliases},
                {"edge: unknown role", A, Set.of("g"), bogus},
                {"edge: empty grants", A, Set.of("g"), emptyGrants},
                {"edge: null mappings", A, Set.of("g"), null},
                {"edge: groups claim as a string", A, "tenant-a-processors", scoped},
                {"edge: padded, null, non-string and blank groups", A, mixedGroups, scoped},
                {"edge: null tenant", null, Set.of("g"), scoped},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : roles) {
            TenantId tenant = (TenantId) c[1];
            Object groups = c[2];
            @SuppressWarnings("unchecked") Map<TenantId, Map<String, Set<String>>> mappings = (Map<TenantId, Map<String, Set<String>>>) c[3];
            tests.add(parity("group.authorizedRoles " + c[0],
                    () -> old.authorizedRoles(tenant, groups, mappings),
                    () -> lib.authorizedRoles(toEntity(tenant), groups, toEntityKeys(mappings))));
        }
        Map<String, Set<String>> grants = Map.of("a", Set.of("DOCUMENT_PROCESSOR"));
        Object[][] matching = {
                {"edge: groups not a collection", "a", grants},
                {"edge: null grants", Set.of("a"), null},
                {"edge: empty grants", Set.of("a"), Map.of()},
                {"edge: no match", Set.of("x"), grants},
                {"edge: padded match", List.of(" a "), grants},
                {"edge: case differs", Set.of("A"), grants},
        };
        for (Object[] c : matching) {
            Object groups = c[1];
            @SuppressWarnings("unchecked") Map<String, Set<String>> g = (Map<String, Set<String>>) c[2];
            tests.add(parity("group.hasMatchingGroup " + c[0], () -> old.hasMatchingGroup(groups, g), () -> lib.hasMatchingGroup(groups, g)));
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ 4. SharedIssuerActivationPreflight (its test + edges)

    @TestFactory
    Stream<DynamicTest> sharedIssuerPreflight() {
        SharedIssuerActivationPreflight old = new SharedIssuerActivationPreflight();
        SharedIssuerPreflight lib = new SharedIssuerPreflight(ROLES);
        Object[][] cases = {
                {"acceptsOnlyExplicitModeWithActiveAuthorizedTenants", true, true, set(A, B), Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN"))},
                {"rejectsInactiveAndUnverifiedTenants: B not a candidate", true, true, set(A), Map.of(B, Set.of("DOCUMENT_PROCESSOR"))},
                {"rejectsInactiveAndUnverifiedTenants: no membership", true, true, set(A), Map.of()},
                {"rejectsInactiveAndUnverifiedTenants: not persisted", false, true, set(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))},
                {"rejectsInactiveAndUnverifiedTenants: not enabled", true, false, set(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))},
                {"refusesPlatformRoleInTenantMembership", true, true, set(A), Map.of(A, Set.of("PLATFORM_OPERATIONS_ADMIN"))},
                {"edge: platform role among others", true, true, set(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR", "PLATFORM_OPERATIONS_ADMIN"))},
                {"edge: empty role set", true, true, set(A), Map.of(A, Set.of())},
                {"edge: null candidates", true, true, null, Map.of(A, Set.of("DOCUMENT_PROCESSOR"))},
                {"edge: null memberships", true, true, set(A), null},
                {"edge: empty candidates", true, true, set(), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : cases) {
            boolean persisted = (Boolean) c[1], enabled = (Boolean) c[2];
            @SuppressWarnings("unchecked") Set<TenantId> candidates = (Set<TenantId>) c[3];
            @SuppressWarnings("unchecked") Map<TenantId, Set<String>> memberships = (Map<TenantId, Set<String>>) c[4];
            tests.add(parity("preflight " + c[0],
                    () -> { old.requireReady(persisted, enabled, candidates, memberships); return "ready"; },
                    () -> { lib.requireReady(persisted, enabled, toEntities(candidates), toEntityKeys(memberships)); return "ready"; }));
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ 5. OidcMachineTenantAdmission (its test)

    @TestFactory
    Stream<DynamicTest> machineAdmission() {
        OidcMachineTenantAdmission old = new OidcMachineTenantAdmission();
        MachineEntityAdmission lib = new MachineEntityAdmission();
        Object[][] cases = {
                {"selectsOnlyUniqueBoundTenant", set(A)},
                {"deniesMissingOrAmbiguousBindings: null", null},
                {"deniesMissingOrAmbiguousBindings: none", set()},
                {"deniesMissingOrAmbiguousBindings: two", set(A, B)},
                {"deniesMissingOrAmbiguousBindings: null element", setWithNull()},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked") Set<TenantId> candidates = (Set<TenantId>) c[1];
            tests.add(parity("machine " + c[0], () -> old.requireUnique(candidates),
                    () -> lib.requireUnique(candidates == null ? null : new HashSet<>(candidates.stream().map(IdentityAdmissionMapping::toEntity).toList()))));
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ 6. OidcTenantMembershipResolver (its test + edges)

    private record Store(Map<TenantId, Map<String, Set<String>>> grants, Set<TenantId> candidates, RuntimeException grantsFailure) {
        static Store of(Map<TenantId, Map<String, Set<String>>> grants, Set<TenantId> candidates) {
            return new Store(grants, candidates, null);
        }
    }

    private static boolean expectedKey(String provider, URI issuer, String audience) {
        return PROVIDER.equals(provider) && ISSUER.equals(issuer) && AUDIENCE.equals(audience);
    }

    private static OidcTenantMembershipResolver oldResolver(Store store, String audience) {
        TenantOidcGroupGrantRepository grants = (p, i, a) -> {
            if (store.grantsFailure() != null) throw store.grantsFailure();
            return expectedKey(p, i, a) ? store.grants() : Map.of();
        };
        ActiveOidcTenantCandidates candidates = (p, i, a) -> expectedKey(p, i, a) ? store.candidates() : Set.of();
        SecurityProperties properties = new SecurityProperties();
        properties.getOidc().setProviderId(PROVIDER);
        properties.getOidc().setAudience(audience);
        return new OidcTenantMembershipResolver(grants, candidates, properties);
    }

    private static EntityMembershipResolver newResolver(Store store, String audience) {
        EntityGroupGrants grants = key -> {
            if (store.grantsFailure() != null) throw store.grantsFailure();
            return expectedKey(key.providerId(), key.issuer(), key.audience()) ? toEntityKeys(store.grants()) : Map.of();
        };
        ActiveEntityCandidates candidates = key -> expectedKey(key.providerId(), key.issuer(), key.audience()) ? toEntities(store.candidates()) : Set.of();
        return new EntityMembershipResolver(grants, candidates, ROLES, new AdmissionSettings(true, true, PROVIDER, audience));
    }

    private static Jwt jwt(URI issuer) {
        Jwt.Builder builder = Jwt.withTokenValue("parity").header("alg", "none").subject("subject-1");
        if (issuer != null) builder.issuer(issuer.toString());
        else builder.claim("preferred_username", "no-issuer");
        return builder.build();
    }

    @TestFactory
    Stream<DynamicTest> membershipResolver() {
        Map<TenantId, Map<String, Set<String>>> twoTenants = Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b", Set.of("TENANT_IT_ADMIN")));
        Map<TenantId, Map<String, Set<String>>> aOnly = Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")));
        Object[][] cases = {
                {"isolatesRolesEvenWhenIdpGroupsContainMultipleTenants: group a", Store.of(twoTenants, set(A, B)), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"isolatesRolesEvenWhenIdpGroupsContainMultipleTenants: groups a+b", Store.of(twoTenants, set(A, B)), Set.of("tenant-a", "tenant-b"), AUDIENCE, ISSUER},
                {"invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership", Store.of(Map.of(A, Map.of("tenant-a", Set.of("PLATFORM_OPERATIONS_ADMIN"))), set(A, B)), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"inactiveTenantCannotBeAdmittedEvenWithGroupGrant", Store.of(Map.of(B, Map.of("tenant-b", Set.of("DOCUMENT_PROCESSOR"))), set(A)), Set.of("tenant-b"), AUDIENCE, ISSUER},
                {"tenantGrantsFromDifferentIssuerCannotAuthorizeThisToken", Store.of(Map.of(), set(A, B)), Set.of("tenant-a", "tenant-b"), AUDIENCE, ISSUER},
                {"noActiveBindingIsNotEnoughEvenWithSignedGroup", Store.of(aOnly, set()), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"authenticatedGroupForAnotherTenantNeverElevatesSelectedTenant", Store.of(twoTenants, set(A, B)), Set.of("tenant-a", "tenant-b"), AUDIENCE, ISSUER},
                {"missingPersistenceResultFailsClosed: null grants", Store.of(null, set(A)), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"missingPersistenceResultFailsClosed: null candidates", Store.of(aOnly, null), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"noGroupsCannotAuthorizeAnyTenant", Store.of(aOnly, set(A, B)), Set.of(), AUDIENCE, ISSUER},
                {"edge: blank audience", Store.of(aOnly, set(A)), Set.of("tenant-a"), " ", ISSUER},
                {"edge: token without issuer", Store.of(aOnly, set(A)), Set.of("tenant-a"), AUDIENCE, null},
                {"edge: other issuer", Store.of(aOnly, set(A)), Set.of("tenant-a"), AUDIENCE, URI.create("https://other.example.test/realms/x")},
                {"edge: trailing-slash issuer (raw iss, no match)", Store.of(aOnly, set(A)), Set.of("tenant-a"), AUDIENCE, URI.create(ISSUER + "/")},
                {"edge: groups claim as a string", Store.of(aOnly, set(A)), "tenant-a", AUDIENCE, ISSUER},
                {"edge: entity with an empty grant map", Store.of(Map.of(A, Map.of()), set(A)), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"edge: unknown role in a matched grant", Store.of(Map.of(A, Map.of("tenant-a", Set.of("BOGUS"))), set(A)), Set.of("tenant-a"), AUDIENCE, ISSUER},
                {"edge: store refuses (adapter's role check)", new Store(aOnly, set(A), new IllegalStateException("Invalid or platform-level tenant group role configuration")), Set.of("tenant-a"), AUDIENCE, ISSUER},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : cases) {
            Store store = (Store) c[1];
            Object groups = c[2];
            String audience = (String) c[3];
            URI issuer = (URI) c[4];
            tests.add(parity("membership.authorized " + c[0],
                    () -> oldResolver(store, audience).authorizedTenants(jwt(issuer), groups),
                    () -> newResolver(store, audience).authorizedEntities(issuer, groups)));
        }
        // The select / selectOnlyIfUnambiguous assertions of the same test.
        Store store = Store.of(twoTenants, set(A, B));
        Map<TenantId, Set<String>> onlyA = Map.of(A, Set.of("DOCUMENT_PROCESSOR"));
        Map<TenantId, Set<String>> both = Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN"));
        tests.add(parity("membership.select isolatesRoles...: B from onlyA",
                () -> oldResolver(store, AUDIENCE).select(B, onlyA), () -> newResolver(store, AUDIENCE).select(toEntity(B), toEntityKeys(onlyA))));
        tests.add(parity("membership.selectOnlyIfUnambiguous isolatesRoles...: both",
                () -> oldResolver(store, AUDIENCE).selectOnlyIfUnambiguous(both), () -> newResolver(store, AUDIENCE).selectOnlyIfUnambiguous(toEntityKeys(both))));
        tests.add(parity("membership.select authenticatedGroup...: A from both",
                () -> oldResolver(store, AUDIENCE).select(A, both), () -> newResolver(store, AUDIENCE).select(toEntity(A), toEntityKeys(both))));
        tests.add(parity("membership.select authenticatedGroup...: B from both",
                () -> oldResolver(store, AUDIENCE).select(B, both), () -> newResolver(store, AUDIENCE).select(toEntity(B), toEntityKeys(both))));
        tests.add(parity("membership.selectOnlyIfUnambiguous noGroupsCannotAuthorizeAnyTenant",
                () -> oldResolver(store, AUDIENCE).selectOnlyIfUnambiguous(Map.of()), () -> newResolver(store, AUDIENCE).selectOnlyIfUnambiguous(Map.of())));
        tests.add(parity("membership.selectOnlyIfUnambiguous edge: null",
                () -> oldResolver(store, AUDIENCE).selectOnlyIfUnambiguous(null), () -> newResolver(store, AUDIENCE).selectOnlyIfUnambiguous(null)));
        return tests.stream();
    }

    // ------------------------------------------------------------------ 7. RequestContextResolver shared-issuer flow
    // RequestContextResolverSecurityTest has no case that reaches the shared-issuer branch (it never enables it), so
    // that branch's own sequence is compared here: membership -> preflight -> selection -> roles of the chosen entity,
    // for the Phase 1 users and refusals.

    private static String oldFlow(Store store, Object groups, String header, boolean enabled) {
        return run(() -> {
            var authorized = oldResolver(store, AUDIENCE).authorizedTenants(jwt(ISSUER), groups);
            new SharedIssuerActivationPreflight().requireReady(true, enabled, authorized.keySet(), authorized);
            TenantId tenant = new OidcTenantSelection().select(header, authorized);
            Map<String, Object> principal = new LinkedHashMap<>();
            principal.put("tenant", tenant);
            principal.put("roles", authorized.get(tenant));
            return principal;
        });
    }

    private static String newFlow(Store store, Object groups, String header, boolean enabled) {
        return runLibrary(() -> {
            var authorized = newResolver(store, AUDIENCE).authorizedEntities(ISSUER, groups);
            new SharedIssuerPreflight(ROLES).requireReady(true, enabled, authorized.keySet(), authorized);
            EntityId entity = new EntitySelection().select(header, authorized);
            Map<String, Object> principal = new LinkedHashMap<>();
            principal.put("tenant", entity);
            principal.put("roles", authorized.get(entity));
            return principal;
        });
    }

    @TestFactory
    Stream<DynamicTest> sharedIssuerRequestFlow() {
        Map<TenantId, Map<String, Set<String>>> phase1Grants = Map.of(
                A, Map.of("lv-a-staff", Set.of("DOCUMENT_SUPERVISOR"), "lv-a-admin", Set.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN")),
                B, Map.of("lv-b-staff", Set.of("DOCUMENT_SUPERVISOR"), "lv-b-admin", Set.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN")));
        Store bothActive = Store.of(phase1Grants, set(A, B));
        Store bDisabled = Store.of(Map.of(A, phase1Grants.get(A)), set(A));
        Set<String> alice = Set.of("lv-a-staff", "lv-a-admin");
        Set<String> carol = Set.of("lv-a-staff", "lv-b-staff");
        Set<String> dave = Set.of("lv-unmapped");
        String a = A.value().toString(), b = B.value().toString();
        Object[][] cases = {
                {"P1 alice, no choice", bothActive, alice, null, true},
                {"P1 alice chooses B", bothActive, alice, b, true},
                {"P2 carol, no choice", bothActive, carol, null, true},
                {"P2 carol chooses A", bothActive, carol, a, true},
                {"P2 carol chooses B", bothActive, carol, b, true},
                {"R carol chooses the demo tenant", bothActive, carol, DEMO.value().toString(), true},
                {"R alice chooses a random id", bothActive, alice, UUID.randomUUID().toString(), true},
                {"R alice malformed choice", bothActive, alice, "not-a-uuid", true},
                {"R dave (no entity)", bothActive, dave, null, true},
                {"R B disabled: carol chooses B", bDisabled, carol, b, true},
                {"R B disabled: carol, no choice", bDisabled, carol, null, true},
                {"R setting off", bothActive, alice, null, false},
                {"R platform role through a group", Store.of(Map.of(A, Map.of("lv-a-staff", Set.of("PLATFORM_OPERATIONS_ADMIN"))), set(A)), alice, null, true},
        };
        List<DynamicTest> tests = new ArrayList<>();
        for (Object[] c : cases) {
            Store store = (Store) c[1];
            Object groups = c[2];
            String header = (String) c[3];
            boolean enabled = (Boolean) c[4];
            tests.add(DynamicTest.dynamicTest("flow " + c[0], () -> {
                String oldOutcome = oldFlow(store, groups, header, enabled);
                String newOutcome = newFlow(store, groups, header, enabled);
                RESULTS.add((oldOutcome.equals(newOutcome) ? "SAME " : "DIFF ") + "flow " + c[0] + " | old=" + oldOutcome + " | new=" + newOutcome);
                assertEquals(oldOutcome, newOutcome, "parity difference in flow: " + c[0]);
            }));
        }
        // Machine path of the same resolver: candidates -> unique.
        Object[][] machine = {{"machine one binding", set(A)}, {"machine two bindings", set(A, B)}, {"machine none", set()}};
        for (Object[] c : machine) {
            @SuppressWarnings("unchecked") Set<TenantId> candidates = (Set<TenantId>) c[1];
            tests.add(parity("flow " + c[0], () -> new OidcMachineTenantAdmission().requireUnique(candidates),
                    () -> new MachineEntityAdmission().requireUnique(toEntities(candidates))));
        }
        return tests.stream();
    }
}
