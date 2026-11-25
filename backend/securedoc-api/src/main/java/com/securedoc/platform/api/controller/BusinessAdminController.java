package com.securedoc.platform.api.controller;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.BusinessAdminConfigurationResponse;
import com.securedoc.platform.api.dto.UpdateBusinessIdentifiersRequest;
import com.securedoc.platform.api.dto.UpdateSensitiveDataPolicyRequest;
import com.securedoc.platform.api.dto.UpdateLifecyclePolicyRequest;
import com.securedoc.platform.api.dto.UpdateDashboardColumnsRequest;
import com.securedoc.platform.api.dto.UpdateDocumentIntelligenceRequest;
import com.securedoc.platform.api.dto.UpdateReportingConfigurationRequest;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.business.admin.BusinessAdminConfigurationService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceConfigurationService;
import com.securedoc.platform.application.operations.model.DashboardColumnSetting;
import com.securedoc.platform.application.operations.service.DashboardColumnConfigurationService;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.RegulationPack;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceMode;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/business-admin")
public class BusinessAdminController {
    private final BusinessAdminConfigurationService service;
    private final RequestContextResolver contextResolver;
    private final DashboardColumnConfigurationService dashboardColumnService;
    private final DocumentIntelligenceConfigurationService documentIntelligenceService;

    public BusinessAdminController(BusinessAdminConfigurationService service,
                                   DashboardColumnConfigurationService dashboardColumnService,
                                   DocumentIntelligenceConfigurationService documentIntelligenceService,
                                   RequestContextResolver contextResolver) {
        this.service = service;
        this.dashboardColumnService = dashboardColumnService;
        this.documentIntelligenceService = documentIntelligenceService;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/configuration")
    @RequireCapability({UserCapability.TENANT_POLICY_MANAGE, UserCapability.TENANT_IDENTIFIER_MANAGE})
    public ResponseEntity<BusinessAdminConfigurationResponse> getConfiguration(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/identifiers")
    @Transactional
    @RequireCapability(UserCapability.TENANT_IDENTIFIER_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateIdentifiers(
            @RequestBody UpdateBusinessIdentifiersRequest body,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        List<CaseFieldSettings.Input> inputs = body == null || body.identifiers() == null
                ? List.of()
                : body.identifiers().stream().map(item -> new CaseFieldSettings.Input(
                        item.id(), item.label(), item.required(), item.primary(), item.searchable(),
                        item.extractable(), item.displayable(), item.validationType(), item.exactLength(),
                        item.minLength(), item.maxLength(), item.caseNormalization(), item.stripSeparators(),
                        item.allowedValues(), item.customPattern(), item.searchMode(), item.sensitive(),
                        item.multiValue(), item.currency(), item.minValue(), item.maxValue())).toList();
        service.updateIdentifiers(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), inputs);
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/dashboard-columns")
    @RequireCapability(UserCapability.TENANT_IDENTIFIER_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateDashboardColumns(
            @RequestBody UpdateDashboardColumnsRequest body,
            HttpServletRequest request) {
        if (body == null || body.columns() == null) throw new ValidationException(LvErrorCode.CFG_001, "Dashboard column configuration is required");
        var principal = contextResolver.resolvePrincipal(request);
        List<DashboardColumnSetting> settings = new java.util.ArrayList<>();
        for (int i = 0; i < body.columns().size(); i++) {
            var column = body.columns().get(i);
            settings.add(new DashboardColumnSetting(column.key(), column.allowed(), column.defaultVisible(), i));
        }
        dashboardColumnService.updateConfiguration(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), settings);
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/sensitive-data-policy")
    @Transactional
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateSensitiveDataPolicy(
            @RequestBody UpdateSensitiveDataPolicyRequest body,
            HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "Sensitive-data policy request is required");
        var principal = contextResolver.resolvePrincipal(request);
        Set<EntityType> enabled = parseTypes(body.enabledEntityTypes());
        ReviewMode reviewMode;
        try {
            reviewMode = ReviewMode.valueOf(body.reviewMode() == null ? "" : body.reviewMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported review mode");
        }
        boolean maskSsnLastFour = body.maskSsnLastFour() != null
                ? body.maskSsnLastFour()
                : service.getConfiguration(principal.tenantId()).sensitiveDataPolicy().maskSsnLastFour();
        // Task 0044: a request without the switch keeps the stored value.
        boolean searchable = body.searchableRedactedPages() != null
                ? body.searchableRedactedPages()
                : service.getConfiguration(principal.tenantId()).sensitiveDataPolicy().searchableRedactedPages();
        service.updateSensitiveDataPolicy(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), enabled,
                body.autoRedactThreshold(), reviewMode, maskSsnLastFour, searchable,
                body.pageBatchSize(), body.pageBatchOverlap(),
                body.enabledPacks() == null ? null : parsePacks(body.enabledPacks()),
                body.keepLastFourKinds() == null ? null : parseTypes(body.keepLastFourKinds()),
                body.visionEnabled(), body.visionOcrThreshold());
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/lifecycle-policy")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateLifecyclePolicy(
            @RequestBody UpdateLifecyclePolicyRequest body, HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "Lifecycle policy request is required");
        var principal = contextResolver.resolvePrincipal(request);
        service.updateLifecyclePolicy(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), body.originalRetentionHours(),
                body.redactedCacheHours(), body.neverExportedHoldHours(), body.finalApprovalRequired());
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/reporting-configuration")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateReportingConfiguration(
            @RequestBody UpdateReportingConfigurationRequest body, HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "Reporting configuration is required");
        var principal = contextResolver.resolvePrincipal(request);
        service.updateReportingConfiguration(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), body.enabledDimensionDefinitionIds(),
                body.deliveryTargetMinutes(), body.deliveryTargetPercent(),
                body.straightThroughTargetPercent(), body.reviewWaitTargetMinutes(), body.reviewWaitTargetPercent(),
                body.minutesPerPage());
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PutMapping("/document-intelligence")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> updateDocumentIntelligence(
            @RequestBody UpdateDocumentIntelligenceRequest body, HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "Document intelligence configuration is required");
        var principal = contextResolver.resolvePrincipal(request);
        DocumentIntelligenceMode mode;
        try {
            mode = DocumentIntelligenceMode.valueOf(body.mode() == null ? "" : body.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported document intelligence mode");
        }
        documentIntelligenceService.update(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), mode, body.activeBundleVersion());
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PostMapping("/document-intelligence/rollback")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> rollbackDocumentIntelligence(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        documentIntelligenceService.rollback(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    @PostMapping("/document-intelligence/last-known-good")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessAdminConfigurationResponse> markDocumentIntelligenceLastKnownGood(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        documentIntelligenceService.markCurrentAsLastKnownGood(principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(toResponse(principal.tenantId(), service.getConfiguration(principal.tenantId())));
    }

    private Set<EntityType> parseTypes(Set<String> rawTypes) {
        if (rawTypes == null) return Set.of();
        Set<EntityType> types = new LinkedHashSet<>();
        for (String raw : rawTypes) {
            try {
                types.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (Exception e) {
                throw new ValidationException(LvErrorCode.CFG_001, "Unsupported sensitive-data category"); // 0023 B7: never echo the value sent
            }
        }
        return Set.copyOf(types);
    }

    private BusinessAdminConfigurationResponse toResponse(
            TenantId tenantId, BusinessAdminConfigurationService.BusinessAdminConfiguration configuration) {
        var identifiers = configuration.identifiers().stream().filter(f -> f.definition().active())
                .map(BusinessAdminConfigurationResponse.BusinessIdentifierAdminResponse::of).toList();
        var switchedOff = configuration.identifiers().stream().filter(f -> !f.definition().active())
                .map(BusinessAdminConfigurationResponse.BusinessIdentifierAdminResponse::of).toList();
        var dashboardColumns = dashboardColumnService.getConfiguration(tenantId).stream()
                .map(column -> new BusinessAdminConfigurationResponse.DashboardAdminColumnResponse(
                        column.key(), column.label(), column.kind(), column.primary(), column.allowed(),
                        column.defaultVisible(), column.displayOrder()))
                .toList();
        var policy = configuration.sensitiveDataPolicy();
        var policyResponse = new BusinessAdminConfigurationResponse.SensitiveDataPolicyResponse(
                policy.enabledEntityTypes().stream().map(Enum::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                policy.autoRedactThreshold(), policy.reviewMode().name(), policy.maskSsnLastFour(),
                policy.searchableRedactedPages(), policy.pageBatches().size(), policy.pageBatches().overlap(),
                names(policy.packs()), names(policy.keepLastFour()), policy.lockedItemsOn(),
                policy.vision().enabled(), policy.vision().ocrThreshold());
        var lifecycle = configuration.lifecyclePolicy();
        var lifecycleResponse = new BusinessAdminConfigurationResponse.LifecyclePolicyResponse(
                lifecycle.originalRetentionHours(), lifecycle.redactedCacheHours(), lifecycle.neverExportedHoldHours(),
                lifecycle.finalApprovalRequired(), TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES,
                TenantLifecyclePolicy.MAX_REDACTED_CACHE_HOURS);
        var intelligence = documentIntelligenceService.getConfiguration(tenantId);
        var intelligenceResponse = new BusinessAdminConfigurationResponse.DocumentIntelligenceResponse(
                intelligence.policy().mode().name(), intelligence.policy().activeBundleVersion(),
                intelligence.policy().lastKnownGoodBundleVersion(), intelligence.policy().configurationVersion(), true,
                intelligence.availableBundles().stream().map(bundle ->
                        new BusinessAdminConfigurationResponse.IntelligenceBundleResponse(
                                bundle.version(), bundle.status().name(), bundle.modelVersion(), bundle.promptVersion(), bundle.detectorVersion(),
                                bundle.ocrRulesVersion(), bundle.profileVersion(), bundle.validationRulesVersion(), bundle.thresholdsVersion(),
                                bundle.description(), bundle.promotedAt())).toList());
        var reporting = configuration.reportingConfiguration();
        var reportingResponse = new BusinessAdminConfigurationResponse.ReportingConfigurationResponse(
                reporting.dimensions().stream().map(d -> new BusinessAdminConfigurationResponse.ReportingDimensionResponse(
                        d.definitionId(), d.key(), d.label(), d.enabled(), d.displayOrder())).toList(),
                reporting.deliveryTargetMinutes(), reporting.deliveryTargetPercent(),
                reporting.straightThroughTargetPercent(), reporting.reviewWaitTargetMinutes(),
                reporting.reviewWaitTargetPercent(), reporting.minutesPerPage());
        return new BusinessAdminConfigurationResponse(identifiers, dashboardColumns, policyResponse, lifecycleResponse, intelligenceResponse, reportingResponse, CATALOG,
                switchedOff, service.sensitiveFieldsAvailable());
    }

    private static final BusinessAdminConfigurationResponse.RedactionCatalogResponse CATALOG =
            new BusinessAdminConfigurationResponse.RedactionCatalogResponse(
                    RedactionCatalog.entries().stream().map(e ->
                            new BusinessAdminConfigurationResponse.CatalogKindResponse(e.kind().name(), e.name(),
                                    e.detection().name(), e.defaultStyle().name(), e.locked(), e.lastFourAllowed())).toList(),
                    Arrays.stream(RegulationPack.values()).map(p ->
                            new BusinessAdminConfigurationResponse.RegulationPackResponse(p.name(), p.displayName(),
                                    p.covers(), p.notCovered(),
                                    RedactionCatalog.kinds().stream().filter(p.kinds()::contains).map(Enum::name).toList(),
                                    RedactionCatalog.kinds().stream().filter(p.suggested()::contains).map(Enum::name).toList()))
                            .toList());

    private static Set<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<RegulationPack> parsePacks(Set<String> raw) {
        Set<RegulationPack> packs = EnumSet.noneOf(RegulationPack.class);
        for (String name : raw) {
            try {
                packs.add(RegulationPack.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException(LvErrorCode.CFG_001, "Unsupported regulation pack");
            }
        }
        return packs;
    }
}
