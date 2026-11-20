package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.domain.business.PolicyPreset;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
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

    private static final double MIN_THRESHOLD = 0.50;
    private static final double MAX_THRESHOLD = 0.99;

    private final BusinessIdentifierDefinitionRepository identifierRepository;
    private final TenantRedactionPolicyRepository policyRepository;
    private final TenantLifecyclePolicyRepository lifecyclePolicyRepository;
    private final ReportingConfigurationRepository reportingConfigurationRepository;
    private final AuditService auditService;

    public BusinessAdminConfigurationService(BusinessIdentifierDefinitionRepository identifierRepository,
                                             TenantRedactionPolicyRepository policyRepository,
                                             TenantLifecyclePolicyRepository lifecyclePolicyRepository,
                                             ReportingConfigurationRepository reportingConfigurationRepository,
                                             AuditService auditService) {
        this.identifierRepository = Objects.requireNonNull(identifierRepository);
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.lifecyclePolicyRepository = Objects.requireNonNull(lifecyclePolicyRepository);
        this.reportingConfigurationRepository = Objects.requireNonNull(reportingConfigurationRepository);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public BusinessAdminConfiguration getConfiguration(TenantId tenantId) {
        List<BusinessIdentifierDefinition> identifiers = identifierRepository.findActiveByTenantId(tenantId);
        TenantRedactionPolicy policy = policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));
        TenantLifecyclePolicy lifecyclePolicy = lifecyclePolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantLifecyclePolicy.defaults(tenantId));
        ReportingConfiguration reportingConfiguration = reportingConfigurationRepository.findByTenantId(tenantId);
        return new BusinessAdminConfiguration(identifiers, policy, lifecyclePolicy, reportingConfiguration);
    }

    public List<BusinessIdentifierDefinition> updateIdentifiers(TenantId tenantId, ActorId actorId,
                                                                 CorrelationId correlationId,
                                                                 List<IdentifierConfigurationInput> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException("At least one active business identifier is required");
        }
        long primaryCount = requested.stream().filter(IdentifierConfigurationInput::primary).count();
        if (primaryCount != 1) {
            throw new ValidationException("Exactly one active business identifier must be Primary");
        }
        IdentifierConfigurationInput primary = requested.stream()
                .filter(IdentifierConfigurationInput::primary)
                .findFirst()
                .orElseThrow();
        if (!primary.required()) {
            throw new ValidationException("The Primary business identifier must also be Required");
        }
        if (!primary.displayable()) {
            throw new ValidationException("The Primary business identifier must be Displayable");
        }

        Set<UUID> requestIds = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (IdentifierConfigurationInput input : requested) {
            requireLabel(input.label());
            if (input.id() != null && !requestIds.add(input.id())) {
                throw new ValidationException("Duplicate business identifier definition id");
            }
            String normalizedLabel = input.label().trim().toLowerCase(Locale.ROOT);
            if (!labels.add(normalizedLabel)) {
                throw new ValidationException("Business identifier labels must be unique");
            }
        }

        List<BusinessIdentifierDefinition> existing = identifierRepository.findAllByTenantId(tenantId);
        Map<UUID, BusinessIdentifierDefinition> existingById = new HashMap<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        for (BusinessIdentifierDefinition definition : existing) {
            existingById.put(definition.id().value(), definition);
            usedKeys.add(definition.key());
        }

        List<BusinessIdentifierDefinition> active = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            IdentifierConfigurationInput input = requested.get(index);
            int displayOrder = index + 1;
            if (input.id() != null) {
                BusinessIdentifierDefinition definition = existingById.get(input.id());
                if (definition == null) {
                    throw new ValidationException("Business identifier definition does not belong to this tenant");
                }
                if (!hasValidationConfiguration(input)) {
                    active.add(definition.reconfigure(input.label(), input.required(), input.primary(),
                            input.searchable(), input.extractable(), input.displayable(), displayOrder));
                } else {
                    BusinessIdentifierCaseNormalization requestedCase = parseCaseNormalization(input.caseNormalization());
                    boolean requestedStripSeparators = Boolean.TRUE.equals(input.stripSeparators());
                    if (requestedCase != definition.caseNormalization() || requestedStripSeparators != definition.stripSeparators()) {
                        throw new ValidationException("Canonical normalization cannot be changed for an existing business identifier in V1");
                    }
                    try {
                        active.add(definition.reconfigure(input.label(), input.required(), input.primary(),
                                input.searchable(), input.extractable(), input.displayable(), displayOrder,
                                parseValidationType(input.validationType()), input.exactLength(), input.minLength(), input.maxLength(),
                                requestedCase, requestedStripSeparators, safeAllowedValues(input.allowedValues()), input.customPattern()));
                    } catch (IllegalArgumentException e) {
                        throw new ValidationException("Invalid validation configuration for " + input.label() + ": " + e.getMessage());
                    }
                }
            } else {
                String key = uniqueKey(input.label(), usedKeys);
                usedKeys.add(key);
                try {
                    active.add(BusinessIdentifierDefinition.create(tenantId, key, input.label(),
                            input.required(), input.primary(), input.searchable(), input.extractable(),
                            input.displayable(), displayOrder, parseValidationType(input.validationType()),
                            input.exactLength(), input.minLength(), input.maxLength(),
                            parseCaseNormalization(input.caseNormalization()), Boolean.TRUE.equals(input.stripSeparators()),
                            safeAllowedValues(input.allowedValues()), input.customPattern()));
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Invalid validation configuration for " + input.label() + ": " + e.getMessage());
                }
            }
        }

        Set<UUID> retainedIds = active.stream().map(d -> d.id().value()).collect(java.util.stream.Collectors.toSet());
        List<BusinessIdentifierDefinition> finalState = new ArrayList<>(active);
        existing.stream()
                .filter(d -> !retainedIds.contains(d.id().value()))
                .map(BusinessIdentifierDefinition::deactivate)
                .forEach(finalState::add);

        identifierRepository.saveAll(finalState);
        auditService.recordSuccess(tenantId, actorId,
                AuditAction.BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED,
                "BusinessIdentifierConfiguration", tenantId.toString(), correlationId,
                "Business identifier configuration changed: activeCount=" + active.size());

        return active.stream().sorted(Comparator.comparingInt(BusinessIdentifierDefinition::displayOrder)).toList();
    }

    public TenantRedactionPolicy updateSensitiveDataPolicy(TenantId tenantId, ActorId actorId,
                                                            CorrelationId correlationId,
                                                            Set<EntityType> enabledTypes,
                                                            double autoRedactThreshold,
                                                            ReviewMode reviewMode,
                                                            boolean maskSsnLastFour) {
        if (enabledTypes == null || enabledTypes.isEmpty()) {
            throw new ValidationException("At least one sensitive-data category must be enabled");
        }
        if (!TenantRedactionPolicy.v1ConfigurableTypes().containsAll(enabledTypes)) {
            throw new ValidationException("Sensitive-data policy contains an unsupported V1 category");
        }
        if (autoRedactThreshold < MIN_THRESHOLD || autoRedactThreshold > MAX_THRESHOLD) {
            throw new ValidationException("Automatic redaction threshold must be between 0.50 and 0.99");
        }
        if (reviewMode == null) {
            throw new ValidationException("Review mode is required");
        }

        Optional<TenantRedactionPolicy> previous = policyRepository.findByTenantId(tenantId);
        if (previous.isPresent() && sameSensitiveDataPolicy(previous.get(), enabledTypes, autoRedactThreshold,
                reviewMode, maskSsnLastFour)) {
            // Task 0017: a save that changes nothing writes no audit record (and nothing is saved).
            return previous.get();
        }
        TenantRedactionPolicy policy = new TenantRedactionPolicy(
                tenantId, enabledTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, Instant.now());
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
                + ", preset=" + PolicyPreset.of(policy);
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
            throw new ValidationException(e.getMessage());
        }
        Optional<TenantLifecyclePolicy> previousLifecycle = lifecyclePolicyRepository.findByTenantId(tenantId);
        if (previousLifecycle.isPresent() && sameLifecyclePolicy(previousLifecycle.get(), policy)) {
            // Task 0017: a save that changes nothing writes no audit record (and nothing is saved).
            return previousLifecycle.get();
        }
        lifecyclePolicyRepository.save(policy);
        auditService.recordSuccess(tenantId, actorId, AuditAction.LIFECYCLE_POLICY_CHANGED,
                "LifecyclePolicy", tenantId.toString(), correlationId,
                "Lifecycle policy changed: originalRetentionHours=" + originalRetentionHours
                        + ", redactedCacheHours=" + redactedCacheHours
                        + ", neverExportedHoldHours=" + neverExportedHoldHours
                        + ", finalApprovalRequired=" + finalApprovalRequired);
        return policy;
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

    private String uniqueKey(String label, Set<String> usedKeys) {
        String base = label.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank() || !Character.isLetter(base.charAt(0))) {
            base = "identifier_" + base;
        }
        if (base.length() > 56) base = base.substring(0, 56);
        String candidate = base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) {
            String ending = "_" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 64 - ending.length())) + ending;
        }
        return BusinessIdentifierDefinition.normalizeKey(candidate);
    }

    private void requireLabel(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException("Business identifier label is required");
        }
        if (value.trim().length() > 128) {
            throw new ValidationException("Business identifier label must be 128 characters or fewer");
        }
    }

    public record BusinessAdminConfiguration(
            List<BusinessIdentifierDefinition> identifiers,
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
                                                                Integer reviewWaitTargetPercent) {
        List<BusinessIdentifierDefinition> active = identifierRepository.findActiveByTenantId(tenantId);
        Map<UUID, BusinessIdentifierDefinition> byId = active.stream()
                .collect(java.util.stream.Collectors.toMap(d -> d.id().value(), d -> d));
        LinkedHashSet<UUID> requested = enabledDimensionDefinitionIds == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(enabledDimensionDefinitionIds);
        List<ReportingConfiguration.Dimension> dimensions = new ArrayList<>();
        int order = 1;
        for (UUID definitionId : requested) {
            BusinessIdentifierDefinition definition = byId.get(definitionId);
            if (definition == null) {
                throw new ValidationException("Reporting dimension does not belong to this tenant or is inactive");
            }
            // V1 reporting dimensions must be low-cardinality, tenant-controlled values.
            // allowedValues is enforced by BusinessIdentifierValidator for every validation type,
            // so a NUMERIC/ALPHANUMERIC/etc. identifier with an explicit small allowed-value set
            // is just as safe for reporting as validationType=ALLOW_LIST.
            if (definition.allowedValues().isEmpty() || definition.allowedValues().size() > 100) {
                throw new ValidationException("Reporting dimensions must define 1-100 allowed values");
            }
            dimensions.add(new ReportingConfiguration.Dimension(definition.id().value(), definition.key(),
                    definition.label(), true, order++));
        }
        // A caller that does not send the percentage (the older admin page) keeps the stored value; it is never reset.
        int reviewPercent = reviewWaitTargetPercent != null ? reviewWaitTargetPercent
                : reportingConfigurationRepository.findByTenantId(tenantId).reviewWaitTargetPercent();
        ReportingConfiguration configuration;
        try {
            configuration = new ReportingConfiguration(dimensions, deliveryTargetMinutes, deliveryTargetPercent,
                    straightThroughTargetPercent, reviewWaitTargetMinutes, reviewPercent);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        reportingConfigurationRepository.save(tenantId, configuration);
        auditService.recordSuccess(tenantId, actorId, AuditAction.REPORTING_CONFIGURATION_CHANGED,
                "ReportingConfiguration", tenantId.toString(), correlationId,
                "Reporting configuration changed: dimensions=" + dimensions.size()
                        + "; deliveryTargetMinutes=" + deliveryTargetMinutes
                        + "; deliveryTargetPercent=" + deliveryTargetPercent
                        + "; straightThroughTargetPercent=" + straightThroughTargetPercent
                        + "; reviewWaitTargetMinutes=" + reviewWaitTargetMinutes
                        + "; reviewWaitTargetPercent=" + reviewPercent);
        return configuration;
    }

    private boolean hasValidationConfiguration(IdentifierConfigurationInput input) {
        return (input.validationType() != null && !input.validationType().isBlank())
                || input.exactLength() != null || input.minLength() != null || input.maxLength() != null
                || (input.caseNormalization() != null && !input.caseNormalization().isBlank())
                || input.stripSeparators() != null || input.allowedValues() != null || input.customPattern() != null;
    }

    private BusinessIdentifierValidationType parseValidationType(String value) {
        if (value == null || value.isBlank()) return BusinessIdentifierValidationType.TEXT;
        try {
            return BusinessIdentifierValidationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unsupported business identifier validation type");
        }
    }

    private BusinessIdentifierCaseNormalization parseCaseNormalization(String value) {
        if (value == null || value.isBlank()) return BusinessIdentifierCaseNormalization.UPPER;
        try {
            return BusinessIdentifierCaseNormalization.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unsupported business identifier case normalization");
        }
    }

    private Set<String> safeAllowedValues(Set<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) cleaned.add(value.trim());
        }
        return Set.copyOf(cleaned);
    }

    public record IdentifierConfigurationInput(
            UUID id,
            String label,
            boolean required,
            boolean primary,
            boolean searchable,
            boolean extractable,
            boolean displayable,
            String validationType,
            Integer exactLength,
            Integer minLength,
            Integer maxLength,
            String caseNormalization,
            Boolean stripSeparators,
            Set<String> allowedValues,
            String customPattern) {
    }
}
