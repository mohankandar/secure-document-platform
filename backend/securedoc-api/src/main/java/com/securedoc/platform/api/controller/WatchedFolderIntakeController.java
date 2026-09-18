package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.IntakeObservationResponse;
import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import com.securedoc.platform.api.platform.WatchedFolderScheduler;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** IT-facing watched-folder surface. Manual scans use the same distributed lease as scheduled scans. */
@RestController
@RequestMapping("/api/v1/intake/watched-folder")
public class WatchedFolderIntakeController {
    private final WatchedFolderIntakeService intakeService;
    private final WatchedFolderScheduler scheduler;
    private final RequestContextResolver contextResolver;

    public WatchedFolderIntakeController(WatchedFolderIntakeService intakeService,
                                         WatchedFolderScheduler scheduler,
                                         RequestContextResolver contextResolver) {
        this.intakeService=intakeService;
        this.scheduler=scheduler;
        this.contextResolver=contextResolver;
    }

    @PostMapping("/scan")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<WatchedFolderScanResponse> scan(HttpServletRequest request) {
        var principal=contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(scheduler.scanManual(principal, contextResolver.resolveCorrelationId(request)));
    }

    @GetMapping("/observations")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<List<IntakeObservationResponse>> observations(@RequestParam(defaultValue="50") int limit,
                                                                         HttpServletRequest request) {
        var principal=contextResolver.resolvePrincipal(request);
        var result=intakeService.listVisible(principal,limit).stream().map(o->new IntakeObservationResponse(
                o.status().name(),o.reasonCode()==null?null:o.reasonCode().name(),o.sourceReference(),
                o.packageId()==null?null:o.packageId().toString(),o.firstSeenAt(),o.visibleAfter(),o.updatedAt())).toList();
        return ResponseEntity.ok(result);
    }
}
