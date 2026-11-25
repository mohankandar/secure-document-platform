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
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.BulkWindow;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Task 0073: a case's priority, changed by a reviewer, and the default priority of a source, set by IT. */
@RestController
@RequestMapping("/api/v1")
public class CasePriorityController {
    private final CasePriorityService priorities;
    private final IntegrationConnectionService connections;
    private final com.securedoc.platform.application.intake.BulkSourceService bulk;
    private final RequestContextResolver contextResolver;

    public CasePriorityController(CasePriorityService priorities, IntegrationConnectionService connections,
                                  com.securedoc.platform.application.intake.BulkSourceService bulk,
                                  RequestContextResolver contextResolver) {
        this.bulk = bulk;
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
        String key = knownSource(principal.tenantId(), source);
        priorities.setSourceDefault(principal.tenantId(), principal.actorId(), key, required(body),
                contextResolver.resolveCorrelationId(request));
        return sources(request);
    }

    /** Task 0079: the Bulk sources of this entity and how far their cases are. Times are the entity's local times. */
    public record BulkSources(List<BulkSource> sources) { }

    public record BulkSource(String source, String from, String to, boolean weekends, boolean open, long done, long waiting,
                             long failed, Double perOpenHour, Instant expectedFinish) { }

    /** Every field is optional: weeknights 19:00 to 06:00 and weekends. */
    public record Window(String from, String to, Boolean weekends) { }

    @GetMapping("/bulk-sources")
    @Transactional(readOnly = true)
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    public BulkSources bulkSources(HttpServletRequest request) {
        return new BulkSources(bulk.progress(contextResolver.resolvePrincipal(request).tenantId()).stream()
                .map(p -> new BulkSource(p.sourceKey(), p.window().from().toString(), p.window().to().toString(),
                        p.window().weekends(), p.open(), p.done(), p.waiting(), p.failed(), p.perOpenHour(),
                        p.expectedFinish())).toList());
    }

    @PutMapping("/bulk-sources/{source}")
    @Transactional
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public BulkSources markBulk(@PathVariable String source, @RequestBody(required = false) Window body,
                                HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        BulkWindow window;
        try {
            window = new BulkWindow(body == null || body.from() == null ? BulkWindow.DEFAULT.from() : LocalTime.parse(body.from()),
                    body == null || body.to() == null ? BulkWindow.DEFAULT.to() : LocalTime.parse(body.to()),
                    body == null || body.weekends() == null ? BulkWindow.DEFAULT.weekends() : body.weekends());
        } catch (DateTimeParseException | IllegalArgumentException notAWindow) {
            throw new ValidationException(LvErrorCode.CFG_001, "from and to must be different times such as 19:00");
        }
        bulk.mark(principal.tenantId(), principal.actorId(), knownSource(principal.tenantId(), source), window,
                contextResolver.resolveCorrelationId(request));
        return bulkSources(request);
    }

    @DeleteMapping("/bulk-sources/{source}")
    @Transactional
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public BulkSources unmarkBulk(@PathVariable String source, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        bulk.unmark(principal.tenantId(), principal.actorId(), knownSource(principal.tenantId(), source),
                contextResolver.resolveCorrelationId(request));
        return bulkSources(request);
    }

    private String knownSource(TenantId tenantId, String source) {
        String key = source;
        if (!CasePriorityService.folder(WatchedFolderSettingsService.INSTALLED_FOLDER).equals(source)) {
            UUID id;
            try {
                id = UUID.fromString(source);
            } catch (IllegalArgumentException notASource) {
                throw new ValidationException(LvErrorCode.CFG_001, "The source is not known");
            }
            // Refuses a connection that is not this entity's.
            key = connections.get(tenantId, IntegrationConnectionId.of(id)).id().value().toString();
        }
        return key;
    }

    private static PipelinePriority required(Priority body) {
        PipelinePriority priority = CasePriorityService.read(body == null ? null : body.priority());
        if (priority == null) throw new ValidationException(LvErrorCode.INT_015, "priority must be URGENT, NORMAL or LOW");
        return priority;
    }
}
