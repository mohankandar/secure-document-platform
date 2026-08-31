package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireRole;
import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-records")
public class AuditController {

    private final ListAuditRecordsQuery listAuditRecordsQuery;
    private final RequestContextResolver contextResolver;

    public AuditController(ListAuditRecordsQuery listAuditRecordsQuery,
                            RequestContextResolver contextResolver) {
        this.listAuditRecordsQuery = listAuditRecordsQuery;
        this.contextResolver = contextResolver;
    }

    @GetMapping
    @RequireRole({"PLATFORM_ADMIN"})
    public ResponseEntity<List<Map<String, Object>>> listAuditRecords(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);

        UUID actorIdUuid = parseUuidParam(actorId, "actorId");
        UUID correlationIdUuid = parseUuidParam(correlationId, "correlationId");
        Instant fromInstant = parseInstantParam(from, "from");
        Instant toInstant = parseInstantParam(to, "to");

        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new ValidationException("'from' must not be after 'to'");
        }

        AuditRecordFilter filter = new AuditRecordFilter(
                principal.tenantId(), action, outcome, resourceType, resourceId,
                actorIdUuid, correlationIdUuid, fromInstant, toInstant, limit);

        List<AuditRecordSummary> results = listAuditRecordsQuery.execute(filter);

        List<Map<String, Object>> response = results.stream()
                .map(this::toResponseMap)
                .toList();

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toResponseMap(AuditRecordSummary record) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", record.id());
        map.put("actorId", record.actorId());
        map.put("action", record.action());
        map.put("resourceType", record.resourceType());
        map.put("resourceId", record.resourceId());
        map.put("outcome", record.outcome());
        map.put("correlationId", record.correlationId());
        if (record.detail() != null) {
            map.put("detail", record.detail());
        }
        map.put("occurredAt", record.occurredAt().toString());
        return map;
    }

    private UUID parseUuidParam(String value, String paramName) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid UUID for parameter '" + paramName + "'");
        }
    }

    private Instant parseInstantParam(String value, String paramName) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("Invalid ISO-8601 timestamp for parameter '" + paramName + "'");
        }
    }
}
