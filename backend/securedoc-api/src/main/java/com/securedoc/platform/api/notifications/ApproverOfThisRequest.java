package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.approval.ApproverCheck;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.TenantBoundaryGuard;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Task 0059: whether the person may approve delivery now, asked when a link is opened and again when it is used. The answer
 * comes from this request's own verified sign-in (never from the list of people messages are sent to): the same entity, not
 * suspended, the same person, and the capability and role the normal approval needs. Outside a request nobody is.
 */
@Component
public class ApproverOfThisRequest implements ApproverCheck {

    private final RequestContextResolver context;
    private final TenantBoundaryGuard entities;
    private final ProductAuthorizationPolicy policy;

    public ApproverOfThisRequest(RequestContextResolver context, TenantBoundaryGuard entities, ProductAuthorizationPolicy policy) {
        this.context = context;
        this.entities = entities;
        this.policy = policy;
    }

    @Override
    public boolean holdsRole(TenantId tenant, EntityId entity, RoleId role, String userId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return false;
        HttpServletRequest request = attributes.getRequest();
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        entities.requireProvisionedTenant(principal);
        String here = principal.tenantId().value().toString();
        return LexaVaultEvents.SUPERVISOR.equals(role)
                && here.equals(tenant.value()) && entity != null && here.equals(entity.value())
                && principal.actorId().value().toString().equals(userId)
                && principal.roles().contains(role.value())
                && policy.has(principal, UserCapability.PACKAGE_APPROVE_DELIVERY);
    }
}
