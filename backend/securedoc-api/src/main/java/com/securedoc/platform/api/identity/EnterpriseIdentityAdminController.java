package com.securedoc.platform.api.identity;

import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin, Sign-in: read-only (task 0041). Sign-in connections are set through setup ({@code idn_login_connection});
 * this screen shows the ACTIVE LexaVault connection of the entity's customer and changes nothing. The R1 draft, test,
 * activate and rollback endpoints are gone: they wrote {@code enterprise_identity_connections}, which sign-in no longer
 * reads (task 0037), so a change there never changed sign-in. Drafts and a test sign-in come later through platform-core.
 *
 * <p>The response keeps the R1 shape so the screens read it as before: {@code staged} is null, there are no rollback
 * candidates and {@code activationSupported} is false. The secret reference is never sent.
 */
@RestController
@RequestMapping("/api/v1/admin/enterprise-identity")
public class EnterpriseIdentityAdminController {

    /** The words both screens show; also in the response so a client can show them as they are. */
    public static final String MANAGED_BY_SETUP = "Sign-in connections are set through setup";

    private final RequestContextResolver context;
    private final PlatformIdentityAdminReads reads;

    public EnterpriseIdentityAdminController(RequestContextResolver context, PlatformIdentityAdminReads reads) {
        this.context = context;
        this.reads = reads;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> overview(HttpServletRequest request) {
        UUID entity = context.resolvePrincipal(request).tenantId().value();
        ConnectionView active = reads.activeConnection(entity).map(c -> ConnectionView.of(entity, c)).orElse(null);
        return ResponseEntity.ok(new Overview(active, null, List.of(), false, 0, true, MANAGED_BY_SETUP));
    }

    /** One login connection as the screens show it. {@code displayName} is the provider ID (idn_ keeps no other name). */
    public record ConnectionView(UUID connectionId, UUID tenantId, String providerId, String providerType,
                                 String displayName, String issuerUri, String browserClientId, String apiAudience,
                                 String status, long configurationVersion) {
        static ConnectionView of(UUID entity, LoginConnection c) {
            return new ConnectionView(c.connectionId(), entity, c.providerId(), c.providerType().name(), c.providerId(),
                    c.issuer().toString(), c.browserClientId(), c.apiAudience(), c.status().name(),
                    c.configurationVersion());
        }
    }

    public record Overview(ConnectionView active, ConnectionView staged, List<ConnectionView> rollbackCandidates,
                           boolean activationSupported, long activationMaxTestAgeSeconds, boolean readOnly,
                           String managedBy) {
    }
}
