package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.EventKind;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.notifications.NotificationSettingsService.BusinessOverview;
import com.securedoc.platform.api.notifications.NotificationSettingsService.EventRequest;
import com.securedoc.platform.api.notifications.NotificationSettingsService.ItOverview;
import com.securedoc.platform.api.notifications.NotificationSettingsService.MailRequest;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
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

/** Admin, Notifications (task 0059): always of the entity the person is in; no call takes an entity. */
@NotificationsOn
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class NotificationSettingsController {

    private final RequestContextResolver context;
    private final NotificationSettingsService service;

    public NotificationSettingsController(RequestContextResolver context, NotificationSettingsService service) {
        this.context = context;
        this.service = service;
    }

    @GetMapping("/it")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<ItOverview> it(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.itOverview(context.resolvePrincipal(request)));
    }

    @PutMapping("/it/mail")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<ItOverview> saveMail(@RequestBody MailRequest body, HttpServletRequest request) {
        service.saveMail(context.resolvePrincipal(request), context.resolveCorrelationId(request), body);
        return it(request);
    }

    @PostMapping("/it/test")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<Void> test(HttpServletRequest request) {
        service.sendTest(context.resolvePrincipal(request), context.resolveCorrelationId(request));
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/it/events/{id}")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<ItOverview> saveTechnical(@PathVariable String id, @RequestBody EventRequest body, HttpServletRequest request) {
        service.saveEvent(context.resolvePrincipal(request), context.resolveCorrelationId(request), EventKind.TECHNICAL, id, body);
        return it(request);
    }

    @GetMapping("/business")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessOverview> business(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.businessOverview(context.resolvePrincipal(request)));
    }

    @PutMapping("/business/events/{id}")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<BusinessOverview> saveBusiness(@PathVariable String id, @RequestBody EventRequest body, HttpServletRequest request) {
        service.saveEvent(context.resolvePrincipal(request), context.resolveCorrelationId(request), EventKind.BUSINESS, id, body);
        return business(request);
    }
}
