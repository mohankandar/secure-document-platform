package com.securedoc.platform.api.context;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves tenant/principal/correlation context from request headers.
 *
 * <p><strong>LOCAL/DEMO ONLY</strong>: Falls back to configured demo values
 * when headers are missing. Production will require proper auth.
 */
@Component
public class RequestContextResolver {

    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_ACTOR_ID = "X-Actor-Id";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private final UUID demoTenantId;
    private final UUID demoActorId;

    public RequestContextResolver(
            @Value("${securedoc.demo.tenant-id}") String demoTenantId,
            @Value("${securedoc.demo.actor-id}") String demoActorId) {
        this.demoTenantId = UUID.fromString(demoTenantId);
        this.demoActorId = UUID.fromString(demoActorId);
    }

    public AuthenticatedPrincipal resolvePrincipal(HttpServletRequest request) {
        UUID tenantId = resolveUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveUuid(request, HEADER_ACTOR_ID, demoActorId);

        return AuthenticatedPrincipal.of(
                ActorId.of(actorId),
                TenantId.of(tenantId),
                "local-demo-user",
                Set.of("ROLE_USER", "ROLE_ADMIN")
        );
    }

    public CorrelationId resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_CORRELATION_ID);
        if (header != null && !header.isBlank()) {
            try {
                return CorrelationId.of(UUID.fromString(header));
            } catch (IllegalArgumentException ignored) {
                // Invalid UUID, generate new
            }
        }
        return CorrelationId.generate();
    }

    private UUID resolveUuid(HttpServletRequest request, String headerName, UUID fallback) {
        String header = request.getHeader(headerName);
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header);
            } catch (IllegalArgumentException ignored) {
                // Invalid UUID, use fallback
            }
        }
        return fallback;
    }
}
