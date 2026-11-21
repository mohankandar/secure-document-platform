package com.securedoc.platform.api.controller;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.compliance.ComplianceReportService;
import com.securedoc.platform.application.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * {@code GET /api/v1/audit-records/count?period=&from=&to=&timeZone=} (task 0019 A1): how many records the
 * compliance report's full audit trail appendix would print for that period, for the entity the person is signed
 * in to. The period is read exactly as {@code POST /api/v1/compliance-reports} reads it. Read-only, not audited.
 */
@RestController
@RequestMapping("/api/v1/audit-records")
public class AuditRecordCountController {

    private final ComplianceReportService service;
    private final RequestContextResolver contextResolver;

    public AuditRecordCountController(ComplianceReportService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/count")
    @RequireCapability(UserCapability.COMPLIANCE_REPORT_FULL)
    public ResponseEntity<Map<String, Long>> count(@RequestParam(required = false) String period,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) String timeZone,
                                                   HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        long count = service.appendixRecordCount(principal.tenantId(),
                new ComplianceReportService.Request(period, date(from, "from"), date(to, "to"), timeZone));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("count", count));
    }

    private static LocalDate date(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException(LvErrorCode.CFG_001, name + " must be a date (YYYY-MM-DD)");
        }
    }
}
