package com.securedoc.platform.api.platform;

import com.securedoc.platform.ai.analysis.AiServerRequestLimit;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.processing.ProcessingCapacity;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.IntakeGate;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Task 0067: the workers of this server and IT's own maximums (for every server). Numbers only. */
@RestController
@RequestMapping("/api/v1/platform-admin/capacity")
public class ProcessingCapacityController {
    private static final Set<String> NAMES = Set.of(PipelineStage.TEXT.name(), PipelineStage.FIND.name(),
            PipelineStage.VISION.name(), PipelineStage.REDACT.name(), PipelineStage.EXPORT.name(), ProcessingCapacity.AI);

    private final ProcessingCapacity capacity;
    private final IntakeGate gate;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;

    public ProcessingCapacityController(ProcessingCapacity capacity, IntakeGate gate,
                                        RequestContextResolver contextResolver, AuditService auditService) {
        this.capacity = capacity;
        this.gate = gate;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
    }

    /** @param aiMaximum null when IT set none for the installation */
    public record Capacity(boolean staged, List<ProcessingCapacity.StageState> stages, List<AiServer> aiServers,
                           Integer aiMaximum, int queueLength, int intakeSlowdown, boolean intakeStopped) { }

    public record AiServer(int number, int requestsAtOnce, int maximum, boolean learned) { }

    /** A null number removes a maximum. */
    public record Maximums(Map<String, Integer> maximums) { }

    @GetMapping
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    public Capacity capacity() {
        List<AiServer> servers = new ArrayList<>();
        if (capacity.staged()) {
            for (var server : AiServerRequestLimit.states()) {
                servers.add(new AiServer(servers.size() + 1, server.limit(), server.max(), server.learned()));
            }
        }
        return new Capacity(capacity.staged(), capacity.stages(), servers, capacity.aiMaximumSet(),
                capacity.queueLength(), gate.slowdown(), gate.full());
    }

    @PutMapping
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    @Transactional
    public Capacity change(@RequestBody Maximums body, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        if (body == null || body.maximums() == null || body.maximums().isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "maximums is required");
        }
        body.maximums().forEach((name, number) -> {
            if (!NAMES.contains(name)) {
                throw new ValidationException(LvErrorCode.CFG_001,
                        "maximums can hold TEXT, FIND, VISION, REDACT, EXPORT and AI");
            }
            if (number != null && (number < 1 || number > ProcessingCapacity.MOST)) {
                throw new ValidationException(LvErrorCode.CFG_001, "A maximum must be between 1 and "
                        + ProcessingCapacity.MOST);
            }
        });
        capacity.setMaximums(body.maximums());
        StringBuilder detail = new StringBuilder();
        new TreeMap<>(body.maximums()).forEach((name, number) -> detail.append(detail.isEmpty() ? "" : ", ")
                .append(name).append('=').append(number == null ? "none" : number.toString()));
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_MAXIMUMS_CHANGED,
                "PlatformAdmin", "capacity", contextResolver.resolveCorrelationId(request), detail.toString());
        return capacity();
    }
}
