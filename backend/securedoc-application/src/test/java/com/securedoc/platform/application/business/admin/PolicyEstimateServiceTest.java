package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.PolicyPreset;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0019 B1, B2, B4: the review rule on hand-made findings, both sides worked out by the same rule, the 20-case
 * floor, the entity boundary, the window and validation.
 */
class PolicyEstimateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String AUTO = "local-rules";
    private static final String MANUAL = "MANUAL_FALLBACK";

    private final TenantId entity = TenantId.of(UUID.randomUUID());
    private final TenantId other = TenantId.of(UUID.randomUUID());
    private final Map<TenantId, TenantRedactionPolicy> saved = new HashMap<>();
    private final InMemoryFindings store = new InMemoryFindings();

    private PolicyEstimateService service() {
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId id) { return Optional.ofNullable(saved.get(id)); }
            public void save(TenantRedactionPolicy p) { saved.put(p.tenantId(), p); }
        };
        return new PolicyEstimateService(policies, store, CLOCK);
    }

    private void savePolicy(TenantId tenant, ReviewMode mode, double threshold) {
        saved.put(tenant, new TenantRedactionPolicy(tenant, Set.of(EntityType.SSN), threshold, mode, true, NOW));
    }

    // --- B1: the rule, one finding -------------------------------------------------------------

    @Test
    void aFindingIsConfirmedAutomaticallyOnlyUnderUncertainOnlyAtOrAboveTheThresholdWithABox() {
        ReviewMode u = ReviewMode.UNCERTAIN_ONLY;
        assertFalse(PolicyEstimateRule.findingGoesToPerson(u, 0.80, 0.81, true), "just above");
        assertFalse(PolicyEstimateRule.findingGoesToPerson(u, 0.80, 0.80, true), "at the threshold");
        assertTrue(PolicyEstimateRule.findingGoesToPerson(u, 0.80, 0.7999, true), "just below");
        assertTrue(PolicyEstimateRule.findingGoesToPerson(u, 0.80, 0.99, false), "unlocated, however sure");
        for (double c : new double[]{0.0, 0.5, 0.8, 0.99, 1.0}) {
            assertTrue(PolicyEstimateRule.findingGoesToPerson(ReviewMode.REVIEW_ALL, 0.80, c, true), "REVIEW_ALL " + c);
            assertTrue(PolicyEstimateRule.findingGoesToPerson(ReviewMode.REVIEW_ALL, 0.50, c, false), "REVIEW_ALL unlocated " + c);
        }
    }

    // --- B1 on cases, through the service ----------------------------------------------------------

    @Test
    void casesAndFindingsAtTheThresholdEdgesUnlocatedAndEmpty() {
        savePolicy(entity, ReviewMode.UNCERTAIN_ONLY, 0.80);
        String above = store.newCase(entity, NOW.minus(Duration.ofDays(1)));
        store.finding(entity, above, 0.81, true, AUTO);
        store.finding(entity, above, 0.95, true, AUTO);
        String at = store.newCase(entity, NOW.minus(Duration.ofDays(2)));
        store.finding(entity, at, 0.80, true, AUTO);
        String below = store.newCase(entity, NOW.minus(Duration.ofDays(3)));
        store.finding(entity, below, 0.79, true, AUTO);
        store.finding(entity, below, 0.99, true, AUTO);
        String unlocated = store.newCase(entity, NOW.minus(Duration.ofDays(4)));
        store.finding(entity, unlocated, 0.99, false, AUTO);
        store.finding(entity, unlocated, 0.99, true, AUTO);
        for (int i = 0; i < 16; i++) store.newCase(entity, NOW.minus(Duration.ofDays(5))); // no findings
        assertEquals(20, store.cases(entity));

        PolicyEstimateService.Estimate e = service().estimate(entity, "UNCERTAIN_ONLY", "0.80");
        assertTrue(e.enoughData());
        assertEquals(20, e.cases());
        assertEquals(2, e.current().casesToPerson(), "the case with 0.79 and the case with an unlocated finding");
        assertEquals(2, e.current().findingsToPerson(), "the 0.79 finding and the unlocated one");

        PolicyEstimateService.Estimate strict = service().estimate(entity, "REVIEW_ALL", null);
        assertEquals(4, strict.proposed().casesToPerson(), "every case with a finding; the 16 empty cases go straight through");
        assertEquals(7, strict.proposed().findingsToPerson(), "every finding");

        // Just below and just above the at-threshold finding move it between the two sides.
        assertEquals(3, service().estimate(entity, "UNCERTAIN_ONLY", "0.81").proposed().casesToPerson());
        assertEquals(1, service().estimate(entity, "UNCERTAIN_ONLY", "0.79").proposed().casesToPerson(),
                "0.79 is now at the threshold: only the unlocated case waits");
        assertEquals(1, service().estimate(entity, "UNCERTAIN_ONLY", "0.50").proposed().casesToPerson(), "only the unlocated one");
        assertEquals(1, service().estimate(entity, "UNCERTAIN_ONLY", "0.50").proposed().findingsToPerson());
    }

    @Test
    void manualRegionsAreLeftOut() {
        savePolicy(entity, ReviewMode.REVIEW_ALL, 0.80);
        String byHand = store.newCase(entity, NOW.minus(Duration.ofDays(1)));
        store.finding(entity, byHand, 1.0, true, MANUAL);
        store.finding(entity, byHand, 1.0, true, MANUAL);
        for (int i = 0; i < 19; i++) store.newCase(entity, NOW.minus(Duration.ofDays(1)));
        PolicyEstimateService.Estimate e = service().estimate(entity, "REVIEW_ALL", null);
        assertEquals(20, e.cases());
        assertEquals(0, e.current().casesToPerson());
        assertEquals(0, e.current().findingsToPerson());
    }

    // --- B4: both sides by the same rule -----------------------------------------------------------

    @Test
    void theSavedAndTheProposedPolicyAreWorkedOutTheSameWay() {
        java.util.Random random = new java.util.Random(19);
        for (int c = 0; c < 60; c++) {
            String id = store.newCase(entity, NOW.minus(Duration.ofHours(1 + random.nextInt(700))));
            int n = random.nextInt(5);
            for (int f = 0; f < n; f++) {
                store.finding(entity, id, Math.round(random.nextDouble() * 100) / 100.0, random.nextInt(6) != 0,
                        random.nextInt(10) == 0 ? MANUAL : AUTO);
            }
        }
        for (ReviewMode mode : ReviewMode.values()) {
            for (String t : new String[]{"0.50", "0.70", "0.80", "0.85", "0.99"}) {
                savePolicy(entity, mode, Double.parseDouble(t));
                PolicyEstimateService.Estimate e = service().estimate(entity, mode.name(), t);
                assertEquals(e.current().casesToPerson(), e.proposed().casesToPerson(), mode + " " + t);
                assertEquals(e.current().findingsToPerson(), e.proposed().findingsToPerson(), mode + " " + t);
                // And both equal the rule applied finding by finding.
                assertEquals(store.byHandRule(entity, mode, Double.parseDouble(t), true), e.current().casesToPerson());
                assertEquals(store.byHandRule(entity, mode, Double.parseDouble(t), false), e.current().findingsToPerson());
            }
        }
    }

    @Test
    void presetsComeFromPolicyPreset() {
        savePolicy(entity, ReviewMode.UNCERTAIN_ONLY, 0.80);
        PolicyEstimateService.Estimate e = service().estimate(entity, "REVIEW_ALL", "0.80");
        assertEquals(PolicyPreset.BALANCED, e.current().preset());
        assertEquals(0.80, e.current().threshold());
        assertEquals(PolicyPreset.STRICT, e.proposed().preset());
        assertNull(e.proposed().threshold(), "the threshold is ignored for REVIEW_ALL");
        assertEquals(PolicyPreset.LIGHT, service().estimate(entity, "uncertain_only", "0.70").proposed().preset());
        assertEquals(PolicyPreset.CUSTOM, service().estimate(entity, "UNCERTAIN_ONLY", "0.85").proposed().preset());
    }

    @Test
    void withNoSavedPolicyTheCurrentSideIsTheDefaultDetectionUses() {
        PolicyEstimateService.Estimate e = service().estimate(entity, "UNCERTAIN_ONLY", "0.70");
        TenantRedactionPolicy defaults = TenantRedactionPolicy.defaults(entity);
        assertEquals(defaults.reviewMode(), e.current().reviewMode());
        assertEquals(defaults.autoRedactThreshold(), e.current().threshold());
    }

    // --- B2: fewer than 20 cases, entity boundary, window -----------------------------------------

    @Test
    void underTwentyCasesOnlyTheCaseCountIsGiven() {
        savePolicy(entity, ReviewMode.UNCERTAIN_ONLY, 0.80);
        for (int i = 0; i < 19; i++) store.finding(entity, store.newCase(entity, NOW.minus(Duration.ofDays(1))), 0.5, true, AUTO);
        PolicyEstimateService.Estimate e = service().estimate(entity, "REVIEW_ALL", null);
        assertFalse(e.enoughData());
        assertEquals(19, e.cases());
        assertEquals(30, e.windowDays());
        for (PolicyEstimateService.Side s : List.of(e.current(), e.proposed())) {
            assertNull(s.casesToPerson());
            assertNull(s.findingsToPerson());
        }
    }

    @Test
    void otherEntitiesCasesNeverCountAndTheWindowIsTheLastThirtyDays() {
        savePolicy(entity, ReviewMode.REVIEW_ALL, 0.80);
        for (int i = 0; i < 20; i++) store.newCase(entity, NOW.minus(Duration.ofDays(2)));
        for (int i = 0; i < 50; i++) store.finding(other, store.newCase(other, NOW.minus(Duration.ofDays(2))), 0.5, false, AUTO);
        // Just outside the window, and in the future: neither counts.
        store.finding(entity, store.newCase(entity, NOW.minus(Duration.ofDays(30)).minusSeconds(1)), 0.5, false, AUTO);
        store.finding(entity, store.newCase(entity, NOW.plusSeconds(1)), 0.5, false, AUTO);
        store.finding(entity, store.newCase(entity, NOW.minus(Duration.ofDays(30))), 0.5, false, AUTO); // first instant counts

        PolicyEstimateService.Estimate e = service().estimate(entity, "REVIEW_ALL", null);
        assertEquals(21, e.cases());
        assertEquals(1, e.current().casesToPerson());
        assertEquals(1, e.current().findingsToPerson());
        assertEquals(NOW.minus(Duration.ofDays(30)), store.lastSince);
        assertEquals(NOW, store.lastUntil);
    }

    // --- B2: validation, as a save ---------------------------------------------------------------

    @Test
    void valuesAreCheckedLikeASave() {
        PolicyEstimateService s = service();
        assertEquals("Review mode is required", assertThrows(ValidationException.class, () -> s.estimate(entity, null, "0.8")).getMessage());
        assertEquals("Review mode is required", assertThrows(ValidationException.class, () -> s.estimate(entity, " ", "0.8")).getMessage());
        assertEquals("Unsupported review mode", assertThrows(ValidationException.class, () -> s.estimate(entity, "AUTO", "0.8")).getMessage());
        for (String bad : new String[]{null, "", "abc", "0.49", "1.0", "0.995", "NaN", "Infinity", "-0.8"}) {
            assertThrows(ValidationException.class, () -> s.estimate(entity, "UNCERTAIN_ONLY", bad), String.valueOf(bad));
        }
        assertEquals(0.50, s.estimate(entity, "UNCERTAIN_ONLY", "0.50").proposed().threshold());
        assertEquals(0.99, s.estimate(entity, "UNCERTAIN_ONLY", "0.99").proposed().threshold());
        // REVIEW_ALL ignores the threshold, even a missing or invalid one.
        for (String ignored : new String[]{null, "", "abc", "7"}) {
            assertNull(s.estimate(entity, "REVIEW_ALL", ignored).proposed().threshold());
        }
    }

    /**
     * The read port over hand-made records, with the adapter's meaning: cases of the tenant received in
     * {@code [since, until)}; manual findings left out; unlocated findings at {@link PolicyEstimateReadPort#UNLOCATED}.
     */
    private static final class InMemoryFindings implements PolicyEstimateReadPort {
        record Case(String id, TenantId tenant, Instant receivedAt) { }
        record Row(TenantId tenant, String caseId, double confidence, boolean located, String detector) { }

        final List<Case> caseList = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        Instant lastSince;
        Instant lastUntil;

        String newCase(TenantId tenant, Instant receivedAt) {
            String id = UUID.randomUUID().toString();
            caseList.add(new Case(id, tenant, receivedAt));
            return id;
        }

        void finding(TenantId tenant, String caseId, double confidence, boolean located, String detector) {
            rows.add(new Row(tenant, caseId, confidence, located, detector));
        }

        long cases(TenantId tenant) {
            return caseList.stream().filter(c -> c.tenant.equals(tenant)).count();
        }

        private List<Case> window(TenantId tenant, Instant since, Instant until) {
            return caseList.stream().filter(c -> c.tenant.equals(tenant))
                    .filter(c -> !c.receivedAt.isBefore(since) && c.receivedAt.isBefore(until)).toList();
        }

        private List<Row> rowsOf(TenantId tenant, Case c) {
            return rows.stream().filter(r -> r.tenant.equals(tenant) && r.caseId.equals(c.id) && !MANUAL.equals(r.detector)).toList();
        }

        /** The rule applied one finding at a time, over the last 30 days: the expected answer. */
        long byHandRule(TenantId tenant, ReviewMode mode, double t, boolean countCases) {
            long n = 0;
            for (Case c : window(tenant, NOW.minus(Duration.ofDays(30)), NOW)) {
                long waiting = rowsOf(tenant, c).stream()
                        .filter(r -> PolicyEstimateRule.findingGoesToPerson(mode, t, r.confidence, r.located)).count();
                n += countCases ? (waiting > 0 ? 1 : 0) : waiting;
            }
            return n;
        }

        @Override
        public Facts facts(TenantId tenant, Instant since, Instant until) {
            lastSince = since;
            lastUntil = until;
            List<Case> win = window(tenant, since, until);
            TreeMap<Double, Long> findings = new TreeMap<>();
            TreeMap<Double, Long> lowest = new TreeMap<>();
            for (Case c : win) {
                Double min = null;
                for (Row r : rowsOf(tenant, c)) {
                    double eff = r.located ? r.confidence : UNLOCATED;
                    findings.merge(eff, 1L, Long::sum);
                    min = min == null ? eff : Math.min(min, eff);
                }
                if (min != null) lowest.merge(min, 1L, Long::sum);
            }
            return new Facts(win.size(),
                    findings.entrySet().stream().map(x -> new Bucket(x.getKey(), x.getValue())).toList(),
                    lowest.entrySet().stream().map(x -> new Bucket(x.getKey(), x.getValue())).toList());
        }
    }
}
