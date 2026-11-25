package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldType;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.operations.TimeSavedRule;
import com.securedoc.platform.application.exception.AuthoredMessages;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.PolicyPreset;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.RegulationPack;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business-facing tenant configuration. This service deliberately owns business terminology
 * sensitive-data policy, and business lifecycle policy. Infrastructure/provider connection details
 * remain IT/Platform configuration.
 */
public final class BusinessAdminConfigurationService {

    static final double MIN_THRESHOLD = 0.50;
    static final double MAX_THRESHOLD = 0.99;

    private final CaseFieldSettings caseFields;
    private final TenantRedactionPolicyRepository policyRepository;
    private final TenantLifecyclePolicyRepository lifecyclePolicyRepository;
    private final ReportingConfigurationRepository reportingConfigurationRepository;
    private final AuditService auditService;

    public BusinessAdminConfigurationService(CaseFieldSettings caseFields,
                                             TenantRedactionPolicyRepository policyRepository,
                                             TenantLifecyclePolicyRepository lifecyclePolicyRepository,
                                             ReportingConfigurationRepository reportingConfigurationRepository,
                                             AuditService auditService) {
        this.caseFields = Objects.requireNonNull(caseFields);
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.lifecyclePolicyRepository = Objects.requireNonNull(lifecyclePolicyRepository);
        this.reportingConfigurationRepository = Objects.requireNonNull(reportingConfigurationRepository);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public BusinessAdminConfiguration getConfiguration(TenantId tenantId) {
        List<CaseFieldSettings.Field> identifiers = caseFields.list(tenantId);
        TenantRedactionPolicy policy = policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));
        TenantLifecyclePolicy lifecyclePolicy = lifecyclePolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantLifecyclePolicy.defaults(tenantId));
        ReportingConfiguration reportingConfiguration = reportingConfigurationRepository.findByTenantId(tenantId);
        return new BusinessAdminConfiguration(identifiers, policy, lifecyclePolicy, reportingConfiguration);
    }

    /** Task 0061: the entity's case fields, in one transaction of the caller. */
    public List<FieldDefinition> updateIdentifiers(TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                                   List<CaseFieldSettings.Input> requested) {
        List<FieldDefinition> active = caseFields.save(tenantId, actorId, requested,
                () -> policy(tenantId).enabledEntityTypes());
        auditService.recordSuccess(tenantId, actorId,
                AuditAction.BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED,
                "BusinessIdentifierConfiguration", tenantId.toString(), correlationId,
                "Business identifier configuration changed: activeCount=" + active.size());
        return active;
    }

    public boolean sensitiveFieldsAvailable() {
        return caseFields.sensitiveAvailable();
    }

    private TenantRedactionPolicy policy(TenantId tenantId) {
        return policyRepository.findByTenantId(tenantId).orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));
    }

    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour) {
        // Task 0044: a caller that does not send the searchable switch keeps the stored value (default on).
        boolean searchable = policyRepository.findByTenantId(tenantId)
                .map(TenantRedactionPolicy::searchableRedactedPages).orElse(true);
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchable);
    }

    /** Task 0044: as above, with the "searchable redacted pages" switch. */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages) {
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchableRedactedPages, null, null);
    }

    /** Task 0049: as above, with the page batch size and overlap; null keeps the stored value (defaults 20 and 2). */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages,
                                                            Integer pageBatchSize,
                                                            Integer pageBatchOverlap) {
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchableRedactedPages, pageBatchSize, pageBatchOverlap, null, null);
    }

    /** Task 0062: as below, with the stored vision settings. */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages,
                                                            Integer pageBatchSize,
                                                            Integer pageBatchOverlap,
                                                            Set<RegulationPack> packs,
                                                            Set<EntityType> keepLastFour) {
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchableRedactedPages, pageBatchSize, pageBatchOverlap, packs,
                keepLastFour, null, null);
    }

    /**
     * Task 0062: as above, with the regulation packs in force and the kinds whose last four digits stay visible; null
     * keeps the stored ones. {@code enabledTypes} decides which kinds are on (a pack kind can be fine-tuned off); the
     * locked items are on whatever it says. Task 0064: and vision on/off and the routing threshold (null keeps them).
     */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages,
                                                            Integer pageBatchSize,
                                                            Integer pageBatchOverlap,
                                                            Set<RegulationPack> packs,
                                                            Set<EntityType> keepLastFour,
                                                            Boolean visionEnabled,
                                                            Double visionOcrThreshold) {
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchableRedactedPages, pageBatchSize, pageBatchOverlap, packs,
                keepLastFour, visionEnabled, visionOcrThreshold, null);
    }

    /**
     * Task 0083: as above, with the second check of the finished file. Null keeps the stored value, or turns it on with
     * a pack that asks for it and was not on before.
     */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages,
                                                            Integer pageBatchSize,
                                                            Integer pageBatchOverlap,
                                                            Set<RegulationPack> packs,
                                                            Set<EntityType> keepLastFour,
                                                            Boolean visionEnabled,
                                                            Double visionOcrThreshold,
                                                            Boolean secondCheckEnabled) {
        return updateSensitiveDataPolicy(tenantId, actorId, correlationId, enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour, searchableRedactedPages, pageBatchSize, pageBatchOverlap, packs,
                keepLastFour, visionEnabled, visionOcrThreshold, secondCheckEnabled, null);
    }

    /** Task 0065: as above, with the document context; null keeps the stored value (off). No pack turns it on. */
    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour,
                                                            boolean searchableRedactedPages,
                                                            Integer pageBatchSize,
                                                            Integer pageBatchOverlap,
                                                            Set<RegulationPack> packs,
                                                            Set<EntityType> keepLastFour,
                                                            Boolean visionEnabled,
                                                            Double visionOcrThreshold,
                                                            Boolean secondCheckEnabled,
                                                            Boolean documentContextEnabled) {
        if (enabledTypes == null || enabledTypes.isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "At least one sensitive-data category must be enabled");
        }
        if (!RedactionCatalog.kinds().containsAll(enabledTypes)) {
            throw new ValidationException(LvErrorCode.CFG_001, "Sensitive-data policy contains an unsupported V1 category");
        }
        if (autoRedactThreshold < MIN_THRESHOLD || autoRedactThreshold > MAX_THRESHOLD) {
            throw new ValidationException(LvErrorCode.CFG_001, "Automatic redaction threshold must be between 0.50 and 0.99");
        }
        if (reviewMode == null) {
            throw new ValidationException(LvErrorCode.CFG_001, "Review mode is required");
        }

        Optional<TenantRedactionPolicy> previous = policyRepository.findByTenantId(tenantId);
        PageBatchSettings storedBatches = previous.map(TenantRedactionPolicy::pageBatches)
                .orElse(PageBatchSettings.DEFAULTS);
        PageBatchSettings batches;
        try {
            batches = new PageBatchSettings(pageBatchSize == null ? storedBatches.size() : pageBatchSize,
                    pageBatchOverlap == null ? storedBatches.overlap() : pageBatchOverlap);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(LvErrorCode.CFG_001, invalid.getMessage());
        }
        VisionSettings storedVision = previous.map(TenantRedactionPolicy::vision).orElse(VisionSettings.DEFAULTS);
        VisionSettings vision;
        try {
            vision = new VisionSettings(visionEnabled == null ? storedVision.enabled() : visionEnabled,
                    visionOcrThreshold == null ? storedVision.ocrThreshold() : visionOcrThreshold);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(LvErrorCode.CFG_001, invalid.getMessage());
        }
        Set<EntityType> lastFour = keepLastFour != null ? keepLastFour
                : previous.map(TenantRedactionPolicy::keepLastFour).orElse(Set.of());
        if (!RedactionCatalog.lastFourAllowed().containsAll(lastFour)) {
            throw new ValidationException(LvErrorCode.CFG_001,
                    "The last four digits can't be kept for a locked item, a date of birth or a credit score");
        }
        Set<RegulationPack> storedPacks = previous.map(TenantRedactionPolicy::packs).orElse(Set.of());
        Set<RegulationPack> newPacks = packs != null ? packs : storedPacks;
        boolean storedCheck = previous.map(TenantRedactionPolicy::secondCheck).orElse(false);
        boolean secondCheck = secondCheckEnabled != null ? secondCheckEnabled : storedCheck
                || newPacks.stream().anyMatch(pack -> pack.secondCheck() && !storedPacks.contains(pack));
        boolean storedContext = previous.map(TenantRedactionPolicy::documentContext).orElse(false);
        boolean documentContext = documentContextEnabled != null ? documentContextEnabled : storedContext;
        TenantRedactionPolicy policy = new TenantRedactionPolicy(
                tenantId, enabledTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, Instant.now(),
                searchableRedactedPages, batches, newPacks, lastFour, true, vision, secondCheck, documentContext);
        if (previous.isPresent() && sameSensitiveDataPolicy(previous.get(), policy.enabledEntityTypes(),
                autoRedactThreshold, reviewMode, maskSsnLastFour)
                && previous.get().searchableRedactedPages() == searchableRedactedPages
                && storedBatches.equals(batches)
                && storedVision.equals(vision)
                && storedCheck == secondCheck
                && storedContext == documentContext
                && previous.get().packs().equals(policy.packs())
                && previous.get().keepLastFour().equals(policy.keepLastFour())
                && previous.get().lockedItemsOn()) {
            // Task 0017: a save that changes nothing writes no audit record (and nothing is saved).
            return previous.get();
        }
        caseFields.requireNoField(tenantId, policy.enabledEntityTypes(),
                previous.map(TenantRedactionPolicy::enabledEntityTypes).orElseGet(() -> policy(tenantId).enabledEntityTypes()));
        policyRepository.save(policy);
        auditService.recordSuccess(tenantId, actorId,
                AuditAction.SENSITIVE_DATA_POLICY_CHANGED,
                "SensitiveDataPolicy", tenantId.toString(), correlationId,
                sensitiveDataPolicyChangeDetail(policy, previous.orElse(null)));
        return policy;
    }

    /**
     * The policy-change audit detail. The first four keys keep their order and format (Admin › Audit trail reads
     * {@code threshold=}); task 0016 appends the type names, the preset worked out here from the saved values, and
     * the same keys for the policy before the change. Type names are category names, never found values.
     */
    static String sensitiveDataPolicyChangeDetail(TenantRedactionPolicy policy, TenantRedactionPolicy previous) {
        return "Sensitive-data policy changed: " + policyKeys(policy)
                + ", previous=" + (previous == null ? "none" : "[" + policyKeys(previous) + "]");
    }

    private static String policyKeys(TenantRedactionPolicy policy) {
        return "enabledCount=" + policy.enabledEntityTypes().size()
                + ", threshold=" + String.format(Locale.ROOT, "%.2f", policy.autoRedactThreshold())
                + ", reviewMode=" + policy.reviewMode()
                + ", maskSsnLastFour=" + policy.maskSsnLastFour()
                + ", enabledTypes=" + policy.enabledEntityTypes().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(","))
                + ", preset=" + PolicyPreset.of(policy)
                // Task 0044: written only when off, so every detail written before stays as it was (no key = on).
                + (policy.searchableRedactedPages() ? "" : ", searchableRedactedPages=false")
                // Task 0049: written only when not the default.
                + (policy.pageBatches().equals(PageBatchSettings.DEFAULTS) ? "" : ", pageBatchSize="
                        + policy.pageBatches().size() + ", pageBatchOverlap=" + policy.pageBatches().overlap())
                // Task 0064: written only when not the default.
                + (policy.vision().enabled() ? "" : ", visionEnabled=false")
                + (policy.vision().ocrThreshold() == VisionSettings.DEFAULTS.ocrThreshold() ? ""
                        : ", visionOcrThreshold=" + String.format(Locale.ROOT, "%.2f", policy.vision().ocrThreshold()))
                // Task 0083: written only when on.
                + (policy.secondCheck() ? ", secondCheck=true" : "")
                // Task 0065: written only when on.
                + (policy.documentContext() ? ", documentContext=true" : "")
                // Task 0062: packs and styles, written only when there are any (no key = none).
                + (policy.packs().isEmpty() ? "" : ", packs=" + sortedNames(policy.packs()))
                + (policy.keepLastFour().isEmpty() ? "" : ", keepLastFour=" + sortedNames(policy.keepLastFour()));
    }

    private static String sortedNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    public TenantLifecyclePolicy updateLifecyclePolicy(TenantId tenantId, ActorId actorId,
                                                       CorrelationId correlationId,
                                                       int originalRetentionHours,
                                                       int redactedCacheHours,
                                                       int neverExportedHoldHours,
                                                       boolean finalApprovalRequired) {
        TenantLifecyclePolicy policy;
        try {
            policy = new TenantLifecyclePolicy(tenantId, originalRetentionHours, redactedCacheHours,
                    neverExportedHoldHours, finalApprovalRequired, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, AuthoredMessages.messageOr(e, "The settings are not valid"));
        }
        Optional<TenantLifecyclePolicy> previousLifecycle = lifecyclePolicyRepository.findByTenantId(tenantId);
        if (previousLifecycle.isPresent() && sameLifecyclePolicy(previousLifecycle.get(), policy)) {
            // Task 0017: a save that changes nothing writes no audit record (and nothing is saved).
            return previousLifecycle.get();
        }
        lifecyclePolicyRepository.save(policy);
        auditService.recordSuccess(tenantId, actorId, AuditAction.LIFECYCLE_POLICY_CHANGED,
                "LifecyclePolicy", tenantId.toString(), correlationId,
                lifecyclePolicyChangeDetail(policy, previousLifecycle.orElse(null)));
        return policy;
    }

    /**
     * The lifecycle-change audit detail. The four keys keep their order and format; task 0018 appends the same keys
     * for the policy before the change, as {@link #sensitiveDataPolicyChangeDetail} does.
     */
    static String lifecyclePolicyChangeDetail(TenantLifecyclePolicy policy, TenantLifecyclePolicy previous) {
        return "Lifecycle policy changed: " + lifecycleKeys(policy)
                + ", previous=" + (previous == null ? "none" : "[" + lifecycleKeys(previous) + "]");
    }

    private static String lifecycleKeys(TenantLifecyclePolicy policy) {
        return "originalRetentionHours=" + policy.originalRetentionHours()
                + ", redactedCacheHours=" + policy.redactedCacheHours()
                + ", neverExportedHoldHours=" + policy.neverExportedHoldHours()
                + ", finalApprovalRequired=" + policy.finalApprovalRequired();
    }

    /** Same values as the stored policy. The threshold compares within 1e-9, as {@link PolicyPreset} does. */
    static boolean sameSensitiveDataPolicy(TenantRedactionPolicy stored, Set<EntityType> enabledTypes,
                                           double autoRedactThreshold, ReviewMode reviewMode,
                                           boolean maskSsnLastFour) {
        return stored.enabledEntityTypes().equals(Set.copyOf(enabledTypes))
                && Math.abs(stored.autoRedactThreshold() - autoRedactThreshold) < 1e-9
                && stored.reviewMode() == reviewMode
                && stored.maskSsnLastFour() == maskSsnLastFour;
    }

    static boolean sameLifecyclePolicy(TenantLifecyclePolicy stored, TenantLifecyclePolicy requested) {
        return stored.originalRetentionHours() == requested.originalRetentionHours()
                && stored.redactedCacheHours() == requested.redactedCacheHours()
                && stored.neverExportedHoldHours() == requested.neverExportedHoldHours()
                && stored.finalApprovalRequired() == requested.finalApprovalRequired();
    }

    public record BusinessAdminConfiguration(
            List<CaseFieldSettings.Field> identifiers,
            TenantRedactionPolicy sensitiveDataPolicy,
            TenantLifecyclePolicy lifecyclePolicy,
            ReportingConfiguration reportingConfiguration) {
        public BusinessAdminConfiguration {
            identifiers = List.copyOf(identifiers);
        }
    }



    public ReportingConfiguration updateReportingConfiguration(TenantId tenantId, ActorId actorId,
                                                                CorrelationId correlationId,
                                                                List<UUID> enabledDimensionDefinitionIds,
                                                                int deliveryTargetMinutes,
                                                                double deliveryTargetPercent,
                                                                double straightThroughTargetPercent,
                                                                int reviewWaitTargetMinutes,
                                                                Integer reviewWaitTargetPercent,
                                                                Double minutesPerPage) {
        Map<UUID, FieldDefinition> byId = caseFields.active(tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::id, d -> d));
        LinkedHashSet<UUID> requested = enabledDimensionDefinitionIds == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(enabledDimensionDefinitionIds);
        List<ReportingConfiguration.Dimension> dimensions = new ArrayList<>();
        int order = 1;
        for (UUID definitionId : requested) {
            FieldDefinition definition = byId.get(definitionId);
            if (definition == null) {
                throw new ValidationException(LvErrorCode.CFG_001, "Reporting dimension does not belong to this tenant or is inactive");
            }
            // Low-cardinality, tenant-controlled values only: one choice of a list, and never a sensitive field.
            if (definition.type() != FieldType.DROPDOWN || definition.spec().sensitive() || definition.spec().multiValue()) {
                throw new ValidationException(LvErrorCode.CFG_001,
                        "Reporting dimensions must be a field with 1-100 allowed values that takes one value and is not sensitive");
            }
            dimensions.add(new ReportingConfiguration.Dimension(definition.id(), definition.key().value(),
                    definition.spec().label(), true, order++));
        }
        ReportingConfiguration stored = reportingConfigurationRepository.findByTenantId(tenantId);
        // A caller that does not send the percentage or the minutes per page (the older admin page) keeps the
        // stored value; it is never reset.
        int reviewPercent = reviewWaitTargetPercent != null ? reviewWaitTargetPercent : stored.reviewWaitTargetPercent();
        double perPage = minutesPerPage != null ? minutesPerPage : stored.minutesPerPage();
        ReportingConfiguration configuration;
        try {
            configuration = new ReportingConfiguration(dimensions, deliveryTargetMinutes, deliveryTargetPercent,
                    straightThroughTargetPercent, reviewWaitTargetMinutes, reviewPercent, perPage);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, AuthoredMessages.messageOr(e, "The settings are not valid"));
        }
        if (sameReportingConfiguration(stored, configuration)) {
            // Task 0020 B2: a save that changes nothing writes no audit record (and nothing is saved), as task 0017.
            return stored;
        }
        reportingConfigurationRepository.save(tenantId, configuration);
        auditService.recordSuccess(tenantId, actorId, AuditAction.REPORTING_CONFIGURATION_CHANGED,
                "ReportingConfiguration", tenantId.toString(), correlationId,
                reportingConfigurationChangeDetail(configuration, stored));
        return configuration;
    }

    /**
     * The report-targets change audit detail. The six keys keep their order and format; task 0020 appends
     * {@code minutesPerPage} and the same keys for the settings before the change ({@code previous=[...]}), as
     * {@link #sensitiveDataPolicyChangeDetail} and {@link #lifecyclePolicyChangeDetail} do. Numbers only.
     */
    static String reportingConfigurationChangeDetail(ReportingConfiguration now, ReportingConfiguration previous) {
        return "Reporting configuration changed: " + reportingKeys(now)
                + "; previous=" + (previous == null ? "none" : "[" + reportingKeys(previous) + "]");
    }

    private static String reportingKeys(ReportingConfiguration c) {
        return "dimensions=" + c.dimensions().size()
                + "; deliveryTargetMinutes=" + c.deliveryTargetMinutes()
                + "; deliveryTargetPercent=" + c.deliveryTargetPercent()
                + "; straightThroughTargetPercent=" + c.straightThroughTargetPercent()
                + "; reviewWaitTargetMinutes=" + c.reviewWaitTargetMinutes()
                + "; reviewWaitTargetPercent=" + c.reviewWaitTargetPercent()
                + "; minutesPerPage=" + TimeSavedRule.minutesPerPageKey(c.minutesPerPage());
    }

    /** Same values, and the same report breakdowns in the same order. Percentages compare within 1e-9. */
    static boolean sameReportingConfiguration(ReportingConfiguration a, ReportingConfiguration b) {
        return a.dimensions().stream().map(ReportingConfiguration.Dimension::definitionId).toList()
                        .equals(b.dimensions().stream().map(ReportingConfiguration.Dimension::definitionId).toList())
                && a.deliveryTargetMinutes() == b.deliveryTargetMinutes()
                && Math.abs(a.deliveryTargetPercent() - b.deliveryTargetPercent()) < 1e-9
                && Math.abs(a.straightThroughTargetPercent() - b.straightThroughTargetPercent()) < 1e-9
                && a.reviewWaitTargetMinutes() == b.reviewWaitTargetMinutes()
                && a.reviewWaitTargetPercent() == b.reviewWaitTargetPercent()
                && Math.abs(a.minutesPerPage() - b.minutesPerPage()) < 1e-9;
    }
}
