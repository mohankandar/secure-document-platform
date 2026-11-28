package com.securedoc.platform.api.controller;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.AccessDeniedAuditRecorder;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.compliance.ComplianceEdition;
import com.securedoc.platform.application.compliance.ComplianceReportService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.identity.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * {@code POST /api/v1/compliance-reports} (task 0017 B1): the compliance report PDF for the entity the person is
 * signed in to. The entity always comes from the request's tenant, never from the body.
 *
 * <ul>
 *   <li>{@code includeFullAuditTrail=false} needs {@code COMPLIANCE_REPORT_SUMMARY} (Business Administrator) or
 *       {@code COMPLIANCE_REPORT_FULL} (U022 step 0: the IT Administrator may untick the full audit trail);</li>
 *   <li>{@code includeFullAuditTrail=true} needs {@code COMPLIANCE_REPORT_FULL} (Tenant IT Administrator).</li>
 * </ul>
 * Anything else is refused with 403 and recorded as a refused {@code COMPLIANCE_REPORT_CREATED}.
 *
 * <p>No outer transaction on purpose: each audit record (success, failure or refusal) is saved on its own, so a
 * refusal or failure is never rolled back with the request.
 */
@RestController
@RequestMapping("/api/v1/compliance-reports")
public class ComplianceReportController {

    public static final String FILE_SHA256_HEADER = "X-Report-File-SHA256";

    private final ComplianceReportService service;
    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public ComplianceReportController(ComplianceReportService service, RequestContextResolver contextResolver,
                                      ProductAuthorizationPolicy authorizationPolicy) {
        this.service = service;
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
    }

    /** The body. Any other field, such as an entity or tenant id, is ignored. */
    public record CreateComplianceReportRequest(String period, LocalDate from, LocalDate to, String timeZone,
                                                Boolean includeFullAuditTrail) { }

    @com.securedoc.platform.api.security.OpenEndpoint("checks its two report capabilities itself and records its own refusal")
    @PostMapping(produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> create(@RequestBody(required = false) CreateComplianceReportRequest body,
                                         HttpServletRequest request) {
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(request);
        if (body == null || body.includeFullAuditTrail() == null) {
            throw new ValidationException(LvErrorCode.CFG_001, "includeFullAuditTrail is required (true or false)");
        }
        ComplianceEdition edition = body.includeFullAuditTrail() ? ComplianceEdition.FULL : ComplianceEdition.SUMMARY;
        if (!mayCreate(principal, edition)) {
            service.recordRefused(principal, correlationId, edition);
            AccessDeniedAuditRecorder.markRecorded(request);
            throw new AccessDeniedException("Insufficient permissions for this operation");
        }
        // The role printed on the report: the IT Administrator stays the IT Administrator for either edition.
        String role = authorizationPolicy.has(principal, UserCapability.COMPLIANCE_REPORT_FULL)
                ? "IT Administrator" : "Business Administrator";

        ComplianceReportService.CreatedReport report = service.create(principal, correlationId,
                new ComplianceReportService.Request(body.period(), body.from(), body.to(), body.timeZone()),
                edition, role);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(report.fileName()).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set(FILE_SHA256_HEADER, report.fileSha256());
        headers.setContentLength(report.pdf().length);
        return ResponseEntity.ok().headers(headers).body(report.pdf());
    }

    /** {@code COMPLIANCE_REPORT_FULL} allows either edition (U022 step 0); {@code COMPLIANCE_REPORT_SUMMARY} only the summary. */
    private boolean mayCreate(AuthenticatedPrincipal principal, ComplianceEdition edition) {
        if (authorizationPolicy.has(principal, UserCapability.COMPLIANCE_REPORT_FULL)) return true;
        return edition == ComplianceEdition.SUMMARY
                && authorizationPolicy.has(principal, UserCapability.COMPLIANCE_REPORT_SUMMARY);
    }
}
