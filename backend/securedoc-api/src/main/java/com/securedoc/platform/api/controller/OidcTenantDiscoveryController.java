package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import com.securedoc.platform.api.security.SecurityContextRefusalRecorder;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.tenant.TenantStatus;
import com.securedoc.platform.infrastructure.identity.LastEntityStore;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.context.SecurityContextException;
import jakarta.servlet.http.HttpServletRequest;
import com.securedoc.platform.api.notifications.NotificationPeople;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The entities the person may enter, before one is chosen (task 0058: in OIDC mode always, with one entity too).
 * SecurityConfig still requires valid OIDC JWT. RequestContextResolver performs
 * ACTIVE connection and tenant-scoped group authorization.
 *
 * <p>Task 0023 C5 (#236): an entity the person is admitted to but that is left out because it is not ACTIVE (or its
 * row is missing) is logged and audited in that entity's log with reason {@code TENANT_NOT_ACTIVE} or
 * {@code TENANT_MISSING}, instead of disappearing without a trace.
 */
@RestController
@RequestMapping("/api/v1/me/tenants")
public class OidcTenantDiscoveryController {
    static final String TENANT_NOT_ACTIVE = "TENANT_NOT_ACTIVE";
    static final String TENANT_MISSING = "TENANT_MISSING";

    private final RequestContextResolver context;
    private final TenantRepository tenantRepository;
    private final SecurityContextRefusalRecorder refusals;
    private final LastEntityStore lastEntity;
    private final ObjectProvider<NotificationPeople> people;

    public OidcTenantDiscoveryController(RequestContextResolver context, TenantRepository tenantRepository) {
        this(context, tenantRepository, null, null);
    }

    /** For tests without remembered entities. */
    public OidcTenantDiscoveryController(RequestContextResolver context, TenantRepository tenantRepository,
                                         SecurityContextRefusalRecorder refusals) {
        this(context, tenantRepository, refusals, null);
    }

    public OidcTenantDiscoveryController(RequestContextResolver context, TenantRepository tenantRepository,
                                         SecurityContextRefusalRecorder refusals, LastEntityStore lastEntity) {
        this(context, tenantRepository, refusals, lastEntity, null);
    }

    @Autowired
    OidcTenantDiscoveryController(RequestContextResolver context, TenantRepository tenantRepository,
                                  SecurityContextRefusalRecorder refusals, LastEntityStore lastEntity,
                                  ObjectProvider<NotificationPeople> people) {
        this.people = people;
        this.lastEntity = lastEntity;
        this.context = context;
        this.tenantRepository = tenantRepository;
        this.refusals = refusals;
    }

    @com.securedoc.platform.api.security.OpenEndpoint("the entities the signed-in person may enter")
    @GetMapping
    public ResponseEntity<List<TenantChoice>> list(HttpServletRequest request) {
        if (context.securityMode() != SecurityMode.OIDC) {
            throw new SecurityContextException("Tenant discovery requires OIDC authentication", "TENANT_DISCOVERY_REQUIRES_OIDC");
        }
        var memberships = context.resolveAvailableOidcTenants();
        // Task 0058: the entity this person worked in last. It only marks one of the choices below, which the sign-in check
        // gave; an entity that is not among them is never added.
        TenantId last = lastEntity == null ? null : context.currentOidcActor().flatMap(lastEntity::find).orElse(null);
        List<TenantChoice> choices = new ArrayList<>();
        for (Map.Entry<TenantId, Set<String>> membership : memberships.entrySet()) {
            Optional<Tenant> tenant = tenantRepository.findById(membership.getKey());
            if (tenant.isPresent() && tenant.get().status() == TenantStatus.ACTIVE) {
                Tenant t = tenant.get();
                choices.add(new TenantChoice(t.id().value().toString(), t.name(), t.slug(), membership.getValue(),
                        t.id().equals(last)));
            } else {
                recordLeftOut(request, membership.getKey(), tenant.isPresent() ? TENANT_NOT_ACTIVE : TENANT_MISSING);
            }
        }
        rememberForNotifications(memberships);
        choices.sort(Comparator.comparing(TenantChoice::tenantId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.copyOf(choices));
    }

    /** Task 0059: which roles this verified sign-in gave where, for messages to the holders of a role. */
    private void rememberForNotifications(Map<TenantId, Set<String>> memberships) {
        NotificationPeople known = people == null ? null : people.getIfAvailable();
        if (known == null) return;
        context.currentOidcActor().ifPresent(actor ->
                known.signedIn(actor, context.verifiedEmail().orElse(null), memberships));
    }

    private void recordLeftOut(HttpServletRequest request, TenantId tenantId, String reason) {
        if (refusals == null || request == null) return;
        ActorId actor = context.currentOidcActor().orElse(null);
        Object correlation = request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
        refusals.recordLeftOutEntity(request, reason, tenantId, actor, correlation == null ? null : correlation.toString());
    }

    public record TenantChoice(String tenantId, String name, String slug, Set<String> roles, boolean lastUsed) {}
}
