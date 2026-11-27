package com.securedoc.platform.api.reports;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.operations.automation.AutomationReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Task 0105: Reports › Automation, of the caller's own entity. Counts only. */
@RestController
@RequestMapping("/api/v1/operations/reports")
public class AutomationReportController {
    private final AutomationReportService automation;
    private final RequestContextResolver contextResolver;

    public AutomationReportController(AutomationReportService automation, RequestContextResolver contextResolver) {
        this.automation = automation;
        this.contextResolver = contextResolver;
    }

    /** {@code period}: TODAY, WEEK or MONTH. */
    @GetMapping("/automation")
    @RequireCapability(UserCapability.REPORT_EXECUTIVE_VIEW)
    @Transactional(readOnly = true)
    public AutomationReportService.Report automation(@RequestParam(defaultValue = "TODAY") String period,
                                                     HttpServletRequest request) {
        return automation.report(contextResolver.resolvePrincipal(request).tenantId(), period);
    }
}
