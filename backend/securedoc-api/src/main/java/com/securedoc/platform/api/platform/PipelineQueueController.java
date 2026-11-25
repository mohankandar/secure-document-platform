package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.processing.ProcessingCapacity;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.intake.BulkSourceService;
import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.application.port.out.QueueWorkPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task 0078 (P6): the queue of each stage and its ETA. An IT Admin sees the numbers of their own entity only; Platform
 * Operations sees every entity's added up. The workers are this server's. Numbers only.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/queue")
public class PipelineQueueController {
    private final QueueView queue;
    private final ProcessingCapacity capacity;
    private final BulkSourceService bulkSources;
    private final ProductAuthorizationPolicy authorization;
    private final RequestContextResolver contextResolver;

    public PipelineQueueController(QueueWorkPort port, ProcessingCapacity capacity, BulkSourceService bulkSources,
                                   ProductAuthorizationPolicy authorization, RequestContextResolver contextResolver) {
        this.queue = new QueueView(port);
        this.capacity = capacity;
        this.bulkSources = bulkSources;
        this.authorization = authorization;
        this.contextResolver = contextResolver;
    }

    public record Stage(String stage, int waiting, int running, int held, int toCome, int workers, int highest,
                        Long oldestWaitingSeconds, Double perMinute, Long etaSeconds) { }

    public record Queue(boolean staged, boolean allEntities, int windowMinutes, Long etaSeconds, List<Stage> stages) { }

    @GetMapping
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    public Queue queue(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        boolean all = authorization.has(principal, UserCapability.PLATFORM_OPERATIONS);
        return of(all ? null : principal.tenantId(), all);
    }

    /** Task 0081: the numbers of one entity, for its reports. */
    public Queue of(TenantId tenantId) {
        return of(java.util.Objects.requireNonNull(tenantId), false);
    }

    private Queue of(TenantId tenantId, boolean all) {
        int window = (int) QueueView.WINDOW.toMinutes();
        if (!capacity.staged()) return new Queue(false, all, window, null, List.of());
        var view = queue.of(tenantId, bulkSources.openSources());
        Map<PipelineStage, ProcessingCapacity.StageState> workers = capacity.stages().stream()
                .collect(Collectors.toMap(ProcessingCapacity.StageState::stage, Function.identity()));
        return new Queue(true, all, window, view.etaSeconds(), view.stages().stream().map(s -> {
            var w = workers.get(s.stage());
            var work = s.work();
            return new Stage(s.stage().name(), work.waiting(), work.running(), work.held(), work.toCome(),
                    w == null ? 0 : w.current(), w == null ? 0 : w.highest(), work.oldestWaitingSeconds(), s.perMinute(),
                    s.etaSeconds());
        }).toList());
    }
}
