package com.securedoc.platform.api.identity;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.identity.EntitySlotService.Answer;
import com.securedoc.platform.api.identity.EntitySlotService.Overview;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.identity.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Admin, Entities (task 0058, Business Admin). Which slots a person may change is decided by {@link EntitySlotService}. */
@RestController
@RequestMapping("/api/v1/admin/entities")
public class EntitySlotController {

    private final RequestContextResolver context;
    private final EntitySlotService service;

    public EntitySlotController(RequestContextResolver context, EntitySlotService service) {
        this.context = context;
        this.service = service;
    }

    public record NameRequest(String name) {
    }

    public record TimeZoneRequest(String timeZone) {
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Overview> overview(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.overview(context.resolvePrincipal(request), context.verifiedSignIn()));
    }

    @PostMapping("/{slotId}/activate")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Answer> activate(@PathVariable String slotId, @RequestBody(required = false) NameRequest body,
                                           HttpServletRequest request) {
        return ResponseEntity.ok(service.activate(principal(request), context.verifiedSignIn(), correlation(request), slotId,
                body == null ? null : body.name()));
    }

    @PutMapping("/{slotId}/name")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Answer> rename(@PathVariable String slotId, @RequestBody(required = false) NameRequest body,
                                         HttpServletRequest request) {
        return ResponseEntity.ok(service.rename(principal(request), context.verifiedSignIn(), correlation(request), slotId,
                body == null ? null : body.name()));
    }

    @PostMapping("/{slotId}/suspend")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Answer> suspend(@PathVariable String slotId, HttpServletRequest request) {
        return ResponseEntity.ok(service.suspend(principal(request), context.verifiedSignIn(), correlation(request), slotId));
    }

    @PostMapping("/{slotId}/resume")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Answer> resume(@PathVariable String slotId, HttpServletRequest request) {
        return ResponseEntity.ok(service.resume(principal(request), context.verifiedSignIn(), correlation(request), slotId));
    }

    @PutMapping("/{slotId}/time-zone")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Answer> timeZone(@PathVariable String slotId, @RequestBody(required = false) TimeZoneRequest body,
                                           HttpServletRequest request) {
        return ResponseEntity.ok(service.setTimeZone(principal(request), context.verifiedSignIn(), correlation(request),
                slotId, Optional.ofNullable(body).map(TimeZoneRequest::timeZone).orElse(null)));
    }

    private AuthenticatedPrincipal principal(HttpServletRequest request) {
        return context.resolvePrincipal(request);
    }

    private CorrelationId correlation(HttpServletRequest request) {
        return context.resolveCorrelationId(request);
    }
}
