package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort.Facts;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.PolicyPreset;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * The policy estimate (task 0019 B2): for the cases the entity received in the last 30 days, how many cases and
 * findings a person would check under the saved policy and under a proposed review mode and threshold. Both sides
 * are worked out by {@link PolicyEstimateRule} from the same stored finding facts, never from what actually
 * happened, so they compare like for like. Sensitive types are not part of it: it uses the findings that exist.
 *
 * <p>Counts only. Reading it is not audited.
 */
public final class PolicyEstimateService {

    public static final int WINDOW_DAYS = 30;
    /** Below this many cases the estimate gives the case count only. */
    public static final int MIN_CASES = 20;

    private final TenantRedactionPolicyRepository policyRepository;
    private final PolicyEstimateReadPort readPort;
    private final Clock clock;

    public PolicyEstimateService(TenantRedactionPolicyRepository policyRepository, PolicyEstimateReadPort readPort,
                                 Clock clock) {
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.readPort = Objects.requireNonNull(readPort);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param threshold the threshold of the proposed policy; {@code null} for none. Used only for
     *                  {@code UNCERTAIN_ONLY}, where it must be a number from 0.50 to 0.99.
     */
    public record Side(ReviewMode reviewMode, Double threshold, PolicyPreset preset, Long casesToPerson,
                       Long findingsToPerson) { }

    public record Estimate(int windowDays, long cases, boolean enoughData, Side current, Side proposed) { }

    /** The raw request values are checked here the way a save checks them; anything unclear is refused. */
    public Estimate estimate(TenantId tenantId, String reviewMode, String threshold) {
        Objects.requireNonNull(tenantId, "tenantId");
        ReviewMode proposedMode = parseMode(reviewMode);
        Double proposedThreshold = proposedMode == ReviewMode.REVIEW_ALL ? null : parseThreshold(threshold);

        TenantRedactionPolicy saved = policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));

        Instant until = clock.instant();
        Instant since = until.minus(Duration.ofDays(WINDOW_DAYS));
        Facts facts = readPort.facts(tenantId, since, until);
        boolean enough = facts.cases() >= MIN_CASES;

        Side current = side(facts, enough, saved.reviewMode(), saved.autoRedactThreshold(),
                PolicyPreset.of(saved.reviewMode(), saved.autoRedactThreshold()));
        // A proposed Strict policy ignores the threshold; any value stands in for the rule, which does not read it.
        Side proposed = side(facts, enough, proposedMode, proposedThreshold,
                PolicyPreset.of(proposedMode, proposedThreshold == null ? 0.0 : proposedThreshold));
        return new Estimate(WINDOW_DAYS, facts.cases(), enough, current, proposed);
    }

    private static Side side(Facts facts, boolean enough, ReviewMode mode, Double threshold, PolicyPreset preset) {
        if (!enough) return new Side(mode, threshold, preset, null, null);
        double t = threshold == null ? 0.0 : threshold;
        return new Side(mode, threshold, preset,
                PolicyEstimateRule.casesToPerson(facts, mode, t),
                PolicyEstimateRule.findingsToPerson(facts, mode, t));
    }

    static ReviewMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) throw new ValidationException(LvErrorCode.CFG_001, "Review mode is required");
        try {
            return ReviewMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported review mode");
        }
    }

    static double parseThreshold(String raw) {
        double value;
        try {
            if (raw == null || raw.isBlank()) throw new NumberFormatException();
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Automatic redaction threshold must be between 0.50 and 0.99");
        }
        if (Double.isNaN(value) || value < BusinessAdminConfigurationService.MIN_THRESHOLD
                || value > BusinessAdminConfigurationService.MAX_THRESHOLD) {
            throw new ValidationException(LvErrorCode.CFG_001, "Automatic redaction threshold must be between 0.50 and 0.99");
        }
        return value;
    }
}
