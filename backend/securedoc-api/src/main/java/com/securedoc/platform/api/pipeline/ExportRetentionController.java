package com.securedoc.platform.api.pipeline;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.service.ExportRetentionService;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0028: the retention period per export destination, set by the Business Admin next to the other retention
 * settings (the screen is task 0029). Hours only. A null period means the default: the redacted-copy retention for
 * the master destination, the master's period for a split destination.
 */
@RestController
@RequestMapping("/api/v1/business-admin/export-retention")
public class ExportRetentionController {
    private final ExportRetentionService service;
    private final RequestContextResolver context;

    public ExportRetentionController(ExportRetentionService service, RequestContextResolver context) {
        this.service = service;
        this.context = context;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ExportRetentionService.RetentionSettings get(HttpServletRequest request) {
        return service.settings(context.resolvePrincipal(request).tenantId());
    }

    /** The master period as given (null: the default); each listed split destination as given (null: the master's). */
    @PutMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ExportRetentionService.RetentionSettings update(@RequestBody UpdateRequest body, HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "Export retention request is required");
        var principal = context.resolvePrincipal(request);
        Map<UUID, Integer> splits = new LinkedHashMap<>();
        if (body.splitDestinations() != null) {
            for (SplitPeriod period : body.splitDestinations()) {
                if (period == null || period.destinationId() == null) {
                    throw new ValidationException(LvErrorCode.CFG_001, "destinationId is required");
                }
                UUID id;
                try {
                    id = UUID.fromString(period.destinationId());
                } catch (RuntimeException invalid) {
                    throw new ValidationException(LvErrorCode.CFG_001, "Invalid destination id");
                }
                if (splits.containsKey(id)) throw new ValidationException(LvErrorCode.CFG_001, "Destination listed twice");
                splits.put(id, period.hours());
            }
        }
        return service.update(principal.tenantId(), principal.actorId(), context.resolveCorrelationId(request),
                body.masterHours(), splits);
    }

    public record UpdateRequest(Integer masterHours, List<SplitPeriod> splitDestinations) { }

    public record SplitPeriod(String destinationId, Integer hours) { }
}
