package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.CasePriorityService;
import com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Task 0073: a case's priority, changed by a reviewer, and the default priority of a source, set by IT. */
@RestController
@RequestMapping("/api/v1")
public class CasePriorityController {
    private final CasePriorityService priorities;
    private final IntegrationConnectionService connections;
    private final RequestContextResolver contextResolver;

    public CasePriorityController(CasePriorityService priorities, IntegrationConnectionService connections,
                                  RequestContextResolver contextResolver) {
        this.priorities = priorities;
        this.connections = connections;
        this.contextResolver = contextResolver;
    }

    public record Priority(String priority) { }

    /** @param sources by source: a connection's id, or folder:installed; a source without one is NORMAL */
    public record SourcePriorities(Map<String, String> sources) { }

    @PutMapping("/packages/{packageId}/priority")
    @Transactional
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public Priority change(@PathVariable UUID packageId, @RequestBody Priority body, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return new Priority(priorities.change(principal.tenantId(), principal.actorId(), PackageId.of(packageId),
                required(body), contextResolver.resolveCorrelationId(request)).name());
    }

    @GetMapping("/source-priorities")
    @Transactional(readOnly = true)
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    public SourcePriorities sources(HttpServletRequest request) {
        Map<String, String> out = new TreeMap<>();
        priorities.sourceDefaults(contextResolver.resolvePrincipal(request).tenantId())
                .forEach((source, priority) -> out.put(source, priority.name()));
        return new SourcePriorities(out);
    }

    @PutMapping("/source-priorities/{source}")
    @Transactional
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public SourcePriorities changeSource(@PathVariable String source, @RequestBody Priority body,
                                         HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        String key = source;
        if (!CasePriorityService.folder(WatchedFolderSettingsService.INSTALLED_FOLDER).equals(source)) {
            UUID id;
            try {
                id = UUID.fromString(source);
            } catch (IllegalArgumentException notASource) {
                throw new ValidationException(LvErrorCode.CFG_001, "The source is not known");
            }
            // Refuses a connection that is not this entity's.
            key = connections.get(principal.tenantId(), IntegrationConnectionId.of(id)).id().value().toString();
        }
        priorities.setSourceDefault(principal.tenantId(), principal.actorId(), key, required(body),
                contextResolver.resolveCorrelationId(request));
        return sources(request);
    }

    private static PipelinePriority required(Priority body) {
        PipelinePriority priority = CasePriorityService.read(body == null ? null : body.priority());
        if (priority == null) throw new ValidationException(LvErrorCode.INT_015, "priority must be URGENT, NORMAL or LOW");
        return priority;
    }
}
