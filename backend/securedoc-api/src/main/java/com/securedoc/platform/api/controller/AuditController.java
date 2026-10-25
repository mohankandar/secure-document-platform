package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-records")
public class AuditController {

    private static final int TIMELINE_MAX_LIMIT = 100;
    private static final int EXPORT_MAX_LIMIT = 5000;

    private static final Set<String> SECURITY_ACTIONS = Set.of(
            "AUTHORIZATION_FAILED", "SECURITY_CONTEXT_INVALID", "CAPABILITY_CHANGED",
            "ENTERPRISE_IDENTITY_DRAFT_SAVED", "ENTERPRISE_IDENTITY_CONNECTION_TESTED",
            "ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED", "ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED",
            "ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK", "ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED",
            "LICENSE_VALIDATION_FAILED", "LICENSE_ENFORCEMENT_DENIED");
    private static final Set<String> ADMINISTRATION_ACTIONS = Set.of(
            "TENANT_CREATED", "TENANT_ACTIVATED", "TENANT_SUSPENDED", "TENANT_DEACTIVATED", "CAPABILITY_CHANGED",
            "BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED", "SENSITIVE_DATA_POLICY_CHANGED",
            "DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED", "DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED",
            "DOCUMENT_INTELLIGENCE_ROLLED_BACK", "DASHBOARD_CONFIGURATION_CHANGED", "LIFECYCLE_POLICY_CHANGED",
            "POLICY_CREATED", "POLICY_VERSION_PUBLISHED", "POLICY_DEACTIVATED", "REPORTING_CONFIGURATION_CHANGED",
            "INTEGRATION_CONNECTION_CREATED", "INTEGRATION_CONNECTION_UPDATED", "INTEGRATION_CONNECTION_ENABLED",
            "INTEGRATION_CONNECTION_DISABLED", "INTEGRATION_CONNECTION_TESTED", "PROCESSING_STORAGE_PROVIDER_CREATED",
            "PROCESSING_STORAGE_PROVIDER_TESTED", "PROCESSING_STORAGE_PROVIDER_ACTIVATED", "PROCESSING_STORAGE_PROVIDER_RETIRED",
            "PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED", "LICENSE_INSTALLED", "BRANDING_CONFIGURATION_CHANGED",
            "ROUTING_RULE_CREATED", "ROUTING_RULE_UPDATED", "ROUTING_RULE_DELETED");
    private static final Set<String> DOCUMENT_ACTIONS = Set.of(
            "PACKAGE_CREATED", "PACKAGE_SUBMITTED", "PACKAGE_PROCESSING_STARTED", "PACKAGE_PROCESSING_RECOVERED",
            "PACKAGE_PROCESSING_RECOVERY_EXHAUSTED", "PACKAGE_PROCESSING_COMPLETED", "PACKAGE_APPROVED", "PACKAGE_REJECTED",
            "PACKAGE_VAULTED", "PACKAGE_CANCELLED", "PACKAGE_FINAL_APPROVAL_REQUIRED", "PACKAGE_FINAL_APPROVED",
            "PACKAGE_REPROCESS_REQUESTED", "PACKAGE_TECHNICAL_RETRY_REQUESTED", "DOCUMENT_UPLOADED",
            "DOCUMENT_EXTRACTION_STARTED", "DOCUMENT_EXTRACTION_COMPLETED", "DOCUMENT_DETECTION_COMPLETED",
            "DOCUMENT_REDACTION_COMPLETED", "DOCUMENT_REDACTION_VERIFIED", "DOCUMENT_PROCESSING_FAILED",
            "FINDING_DETECTED", "FINDING_CONFIRMED", "FINDING_DISMISSED", "FINDING_REDACTED", "FINDING_OVERRIDDEN",
            "FINDING_BOUNDING_BOX_UPDATED", "REVIEW_TASK_CREATED", "REVIEW_TASK_ASSIGNED", "REVIEW_STARTED",
            "REVIEW_APPROVED", "REVIEW_REJECTED", "REVIEW_ESCALATED", "BUSINESS_MANUAL_FALLBACK_STARTED",
            "BUSINESS_MANUAL_FALLBACK_REGION_ADDED", "BUSINESS_MANUAL_FALLBACK_REGION_REMOVED", "BUSINESS_MANUAL_FALLBACK_SUBMITTED");
    private static final Set<String> EXPORT_ACTIONS = Set.of(
            "DOCUMENT_DOWNLOADED", "DOCUMENT_EXPORTED", "DOCUMENT_EXPORT_FAILED", "PACKAGE_EXPORT_RECORDED",
            "REPORT_CSV_EXPORTED", "AUDIT_EXPORTED", "DERIVED_DOCUMENT_DELIVERED", "DERIVED_DOCUMENT_DELIVERY_FAILED");
    private static final Set<String> SYSTEM_ACTIONS = Set.of(
            "RETENTION_SCHEDULED", "REDACTED_ARTIFACT_PURGED", "ORIGINAL_ARTIFACT_PURGED", "PACKAGE_PURGED",
            "PACKAGE_HIDDEN_FROM_DASHBOARD", "TECHNICAL_ATTENTION_AUTO_CLOSED", "POLLING_RUN_STARTED",
            "POLLING_RUN_COMPLETED", "POLLING_RUN_FAILED", "POLLING_SOURCE_ACKNOWLEDGED", "ROUTING_ATTENTION_REQUIRED",
            "DERIVED_DOCUMENT_CREATED", "DERIVED_DOCUMENT_DELIVERED", "DERIVED_DOCUMENT_DELIVERY_FAILED");

    private final ListAuditRecordsQuery listAuditRecordsQuery;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;

    public AuditController(ListAuditRecordsQuery listAuditRecordsQuery,
                           RequestContextResolver contextResolver,
                           AuditService auditService) {
        this.listAuditRecordsQuery = listAuditRecordsQuery;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireCapability(UserCapability.AUDIT_VIEW)
    public ResponseEntity<List<Map<String, Object>>> listAuditRecords(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "false") boolean includeRoutineActivity,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        AuditRecordFilter filter = buildFilter(principal, category, action, outcome, resourceType, resourceId,
                actorId, correlationId, from, to, Math.min(limit, TIMELINE_MAX_LIMIT), includeRoutineActivity);
        return ResponseEntity.ok(listAuditRecordsQuery.execute(filter).stream().map(this::toResponseMap).toList());
    }

    @GetMapping("/export")
    @RequireCapability(UserCapability.AUDIT_VIEW)
    public ResponseEntity<?> exportAuditRecords(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "false") boolean includeRoutineActivity,
            HttpServletRequest httpRequest) {

        String normalizedFormat = format.trim().toLowerCase(Locale.ROOT);
        if (!normalizedFormat.equals("csv") && !normalizedFormat.equals("json")) {
            throw new ValidationException("Audit export format must be 'csv' or 'json'");
        }
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        AuditRecordFilter filter = buildFilter(principal, category, action, outcome, resourceType, resourceId,
                actorId, correlationId, from, to, EXPORT_MAX_LIMIT, includeRoutineActivity);
        List<AuditRecordSummary> records = listAuditRecordsQuery.execute(filter);

        String safeDetail = "Audit exported: format=" + normalizedFormat.toUpperCase(Locale.ROOT)
                + ", category=" + safe(category) + ", action=" + safe(action) + ", outcome=" + safe(outcome)
                + ", resourceType=" + safe(resourceType) + ", from=" + safe(from) + ", to=" + safe(to)
                + ", resultCount=" + records.size();
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.AUDIT_EXPORTED,
                "AuditTimeline", normalizedFormat.toUpperCase(Locale.ROOT), contextResolver.resolveCorrelationId(httpRequest), safeDetail);

        String filename = "lexavault-audit." + normalizedFormat;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        if (normalizedFormat.equals("json")) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.ok().headers(headers).body(records.stream().map(this::toResponseMap).toList());
        }
        headers.setContentType(new MediaType("text", "csv"));
        return ResponseEntity.ok().headers(headers).body(toCsv(records));
    }

    private AuditRecordFilter buildFilter(AuthenticatedPrincipal principal, String category, String action, String outcome,
                                          String resourceType, String resourceId, String actorId, String correlationId,
                                          String from, String to, int limit, boolean includeRoutineActivity) {
        UUID actorIdUuid = parseUuidParam(actorId, "actorId");
        UUID correlationIdUuid = parseUuidParam(correlationId, "correlationId");
        Instant fromInstant = parseInstantParam(from, "from");
        Instant toInstant = parseInstantParam(to, "to");
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new ValidationException("'from' must not be after 'to'");
        }
        Set<String> categoryActions = categoryActions(category);
        Set<String> categoryOutcomes = "FAILURES_DENIED".equals(normalizeCategory(category)) ? Set.of("FAILURE", "DENIED") : Set.of();
        boolean includeRoutineNoOpScans = includeRoutineActivity || "WATCHED_FOLDER_SCAN_TRIGGERED".equals(action);
        return new AuditRecordFilter(principal.tenantId(), blankToNull(action), blankToNull(outcome), blankToNull(resourceType),
                blankToNull(resourceId), actorIdUuid, correlationIdUuid, fromInstant, toInstant, limit,
                includeRoutineNoOpScans, categoryActions, categoryOutcomes);
    }

    private Set<String> categoryActions(String category) {
        return switch (normalizeCategory(category)) {
            case "", "MEANINGFUL" -> Set.of();
            case "SECURITY" -> SECURITY_ACTIONS;
            case "ADMINISTRATION" -> ADMINISTRATION_ACTIONS;
            case "DOCUMENTS" -> DOCUMENT_ACTIONS;
            case "EXPORTS" -> EXPORT_ACTIONS;
            case "FAILURES_DENIED" -> Set.of();
            case "SYSTEM" -> SYSTEM_ACTIONS;
            default -> throw new ValidationException("Unknown audit category");
        };
    }

    private String normalizeCategory(String category) {
        return category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> toResponseMap(AuditRecordSummary record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.id());
        map.put("actorId", record.actorId());
        map.put("action", record.action());
        map.put("resourceType", record.resourceType());
        map.put("resourceId", record.resourceId());
        map.put("outcome", record.outcome());
        map.put("correlationId", record.correlationId());
        if (record.detail() != null) map.put("detail", record.detail());
        map.put("occurredAt", record.occurredAt().toString());
        return map;
    }

    private String toCsv(List<AuditRecordSummary> records) {
        StringBuilder csv = new StringBuilder("occurredAt,action,outcome,actorId,resourceType,resourceId,correlationId,detail\r\n");
        for (AuditRecordSummary r : records) {
            csv.append(csv(r.occurredAt().toString())).append(',').append(csv(r.action())).append(',')
                    .append(csv(r.outcome())).append(',').append(csv(r.actorId())).append(',')
                    .append(csv(r.resourceType())).append(',').append(csv(r.resourceId())).append(',')
                    .append(csv(r.correlationId())).append(',').append(csv(r.detail())).append("\r\n");
        }
        return csv.toString();
    }

    private String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String safe(String value) { return value == null || value.isBlank() ? "ALL" : value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private UUID parseUuidParam(String value, String paramName) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException e) { throw new ValidationException("Invalid UUID for parameter '" + paramName + "'"); }
    }

    private Instant parseInstantParam(String value, String paramName) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (DateTimeParseException e) { throw new ValidationException("Invalid ISO-8601 timestamp for parameter '" + paramName + "'"); }
    }
}
