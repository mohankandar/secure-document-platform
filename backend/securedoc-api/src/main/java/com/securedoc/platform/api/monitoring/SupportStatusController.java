package com.securedoc.platform.api.monitoring;

import com.documentplatform.errors.FailureLog;
import com.documentplatform.errors.PlatformError;
import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.spring.DiagnosticBundles;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Task 0057: Support, Status: every dependency check with its code, cause and what to do, and the last failures with their
 * references. The diagnostic bundle is for the IT Admin only and its download is audited. Codes, causes, results and
 * settings without secrets; never document content, field values or secrets.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportStatusController {

    private static final int FAILURES = 20;

    private final DependencyStatus status;
    private final DiagnosticBundles bundles;
    private final AuditService audit;
    private final RequestContextResolver context;

    public SupportStatusController(DependencyStatus status, DiagnosticBundles bundles, AuditService audit,
                                   RequestContextResolver context) {
        this.status = status;
        this.bundles = bundles;
        this.audit = audit;
        this.context = context;
    }

    @GetMapping("/status")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ResponseEntity<StatusResponse> status(HttpServletRequest request) {
        context.resolvePrincipal(request);
        List<CheckResult> results = status.results();
        return ResponseEntity.ok(new StatusResponse(overall(results).name(), results.stream().map(CheckView::of).toList(),
                FailureLog.recent(FAILURES).stream().map(FailureView::of).toList()));
    }

    @GetMapping("/diagnostic-bundle")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<byte[]> bundle(HttpServletRequest request) throws IOException {
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        bundles.write(zip);
        audit.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.DIAGNOSTIC_BUNDLE_DOWNLOADED, "Support",
                "diagnostic-bundle", context.resolveCorrelationId(request), "Diagnostic bundle downloaded");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("lexavault-diagnostic-bundle.zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(zip.toByteArray());
    }

    static CheckState overall(List<CheckResult> results) {
        if (results.stream().anyMatch(r -> r.state() == CheckState.DOWN)) return CheckState.DOWN;
        return results.stream().anyMatch(r -> r.state() == CheckState.HAVING_PROBLEMS) ? CheckState.HAVING_PROBLEMS
                : CheckState.WORKING;
    }

    public record StatusResponse(String overall, List<CheckView> checks, List<FailureView> failures) {
    }

    public record CheckView(String name, String group, String state, String code, String cause, String whatToDo,
                            boolean required, Instant checkedAt) {
        static CheckView of(CheckResult r) {
            boolean working = r.state() == CheckState.WORKING;
            return new CheckView(r.name(), r.group(), r.state().name(), r.code().code(), working ? null : r.cause(),
                    working ? null : r.fix(), r.requiredAtStartup(), r.checkedAt());
        }
    }

    public record FailureView(Instant at, String code, String reference, String cause, String whatToDo) {
        static FailureView of(PlatformError e) {
            return new FailureView(e.at(), e.code().code(), e.reference(), e.cause(), e.fix());
        }
    }
}
