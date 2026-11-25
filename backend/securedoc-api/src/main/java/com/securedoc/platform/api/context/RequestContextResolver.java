package com.securedoc.platform.api.context;

import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.api.security.MachineRoutes;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
import com.documentplatform.identity.admission.EntitySelection;
import com.documentplatform.identity.admission.MachineEntityAdmission;
import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.message;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.refusingAsSecurityContext;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntities;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntityKeys;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toTenant;

/**
 * Resolves the application principal from the configured trust boundary.
 * LOCAL may use explicit demo headers/fallbacks. OIDC uses verified JWT claims only.
 */
@Component
public class RequestContextResolver {

    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_ACTOR_ID = "X-Actor-Id";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    private static final String HEADER_ACTOR_ROLES = "X-Actor-Roles";
    private static final String HEADER_INTEGRATION_CLIENT = "X-Integration-Client";
    private static final String PRINCIPAL_ATTRIBUTE = RequestContextResolver.class.getName() + ".principal";
    private static final String INTEGRATION_PRINCIPAL_ATTRIBUTE = RequestContextResolver.class.getName() + ".integrationPrincipal";

    /** A person's principal kept for the rest of the request, with the values its display name was bound from. */
    private record Resolved(AuthenticatedPrincipal principal, String displayNameClaim, String username) { }


    private final UUID demoTenantId;
    private final UUID demoActorId;
    private final SecurityProperties securityProperties;
    private final OidcTenantMembershipResolver oidcTenantMembershipResolver;
    private final ActiveOidcMachineTenantBindings machineTenantBindings;
    // platform-identity admission rules; refusals are mapped to SecurityContextException (403) at each call.
    private final EntitySelection tenantSelection = new EntitySelection();
    private final MachineEntityAdmission machineAdmission = new MachineEntityAdmission();
    private final TrustedActorResolver trustedActorResolver;
    private final OidcJwtIdentityNormalizer oidcIdentityNormalizer;
    private final RequestActorDisplayName actorDisplayName;

    /** For tests that do not look at audit display names: the name is bound to a holder nothing reads. */
    public RequestContextResolver(
            String demoTenantId,
            String demoActorId,
            SecurityProperties securityProperties,
            OidcTenantMembershipResolver oidcTenantMembershipResolver,
            ActiveOidcMachineTenantBindings machineTenantBindings,
            TrustedActorResolver trustedActorResolver,
            OidcJwtIdentityNormalizer oidcIdentityNormalizer) {
        this(demoTenantId, demoActorId, securityProperties, oidcTenantMembershipResolver,
                machineTenantBindings, trustedActorResolver, oidcIdentityNormalizer,
                new RequestActorDisplayName());
    }

    @Autowired
    public RequestContextResolver(
            @Value("${securedoc.demo.tenant-id}") String demoTenantId,
            @Value("${securedoc.demo.actor-id}") String demoActorId,
            SecurityProperties securityProperties,
            OidcTenantMembershipResolver oidcTenantMembershipResolver,
            ActiveOidcMachineTenantBindings machineTenantBindings,
            TrustedActorResolver trustedActorResolver,
            OidcJwtIdentityNormalizer oidcIdentityNormalizer,
            RequestActorDisplayName actorDisplayName) {
        this.demoTenantId = UUID.fromString(demoTenantId);
        this.demoActorId = UUID.fromString(demoActorId);
        this.securityProperties = securityProperties;
        this.oidcTenantMembershipResolver = oidcTenantMembershipResolver;
        this.machineTenantBindings = machineTenantBindings;
        this.trustedActorResolver = trustedActorResolver;
        this.oidcIdentityNormalizer = oidcIdentityNormalizer;
        this.actorDisplayName = actorDisplayName;
    }

    public AuthenticatedPrincipal resolvePrincipal(HttpServletRequest request) {
        // Resolved once per request (task 0037 step 0, A1 redone): the interceptors and the controller all ask, and in
        // shared-issuer mode each resolution reads the grant and candidate stores. The inputs (verified token,
        // headers) cannot change within a request, so the first answer is reused; the display name is bound again
        // from the same values, as each call did before. Refusals are not stored: a later call refuses again.
        AuthenticatedPrincipal principal;
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof Resolved known) {
            principal = known.principal();
            actorDisplayName.bind(principal.actorId(), known.displayNameClaim(), known.username());
        } else {
            principal = securityProperties.getMode() == SecurityMode.LOCAL
                    ? resolveLocalPrincipal(request)
                    : resolveOidcPrincipal(request);
        }
        StructuredLogContext.put("tenantId", principal.tenantId());
        StructuredLogContext.put("actorId", principal.actorId());
        return principal;
    }

    /**
     * Resolves a service/integration identity without requiring a product user-role mapping.
     * The caller is authorized later by binding this authenticated principal to an enabled
     * tenant PUSH_API Integration Connection. Production identity still comes only from the verified JWT.
     */
    public AuthenticatedPrincipal resolveIntegrationPrincipal(HttpServletRequest request) {
        AuthenticatedPrincipal principal = request.getAttribute(INTEGRATION_PRINCIPAL_ATTRIBUTE) instanceof AuthenticatedPrincipal known
                ? known
                : securityProperties.getMode() == SecurityMode.LOCAL
                        ? resolveLocalIntegrationPrincipal(request)
                        : resolveOidcIntegrationPrincipal(MachineRoutes.scanner(request));
        request.setAttribute(INTEGRATION_PRINCIPAL_ATTRIBUTE, principal);
        // A machine client is not a person: its audit records carry no display name.
        actorDisplayName.clear();
        StructuredLogContext.put("tenantId", principal.tenantId());
        StructuredLogContext.put("actorId", principal.actorId());
        return principal;
    }

    /**
     * Bootstrap tenant choices before a selected tenant exists.
     * Only in OIDC mode, through the stored connection (task 0058: always, whether the customer has one entity or several).
     * Does not create a business principal or assign any tenant context.
     */
    public Map<TenantId, Set<String>> resolveAvailableOidcTenants() {
        if (securityProperties.getMode() != SecurityMode.OIDC
                || !securityProperties.getOidc().isPersistentConnectionEnabled()) {
            throw new SecurityContextException("OIDC tenant discovery is not enabled", "TENANT_DISCOVERY_NOT_ENABLED");
        }
        var jwt = requireOidcToken().getToken();
        AuthenticatedIdentity identity = normalizeIdentity(jwt);
        return oidcTenantMembershipResolver.authorizedTenants(jwt, identity.groups());
    }

    public SecurityMode securityMode() {
        return securityProperties.getMode();
    }

    public CorrelationId resolveCorrelationId(HttpServletRequest request) {
        Object established = request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
        if (established instanceof UUID uuid) {
            StructuredLogContext.put("correlationId", uuid);
            return CorrelationId.of(uuid);
        }
        String header = request.getHeader(HEADER_CORRELATION_ID);
        if (header != null && !header.isBlank()) {
            try {
                UUID uuid = UUID.fromString(header.trim());
                StructuredLogContext.put("correlationId", uuid);
                return CorrelationId.of(uuid);
            } catch (IllegalArgumentException ignored) {
                // Invalid correlation metadata is replaced, never used as identity truth.
            }
        }
        CorrelationId generated = CorrelationId.generate();
        StructuredLogContext.put("correlationId", generated);
        return generated;
    }

    private AuthenticatedPrincipal resolveLocalPrincipal(HttpServletRequest request) {
        UUID tenantId = resolveLocalUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveLocalUuid(request, HEADER_ACTOR_ID, demoActorId);
        Set<String> roles = resolveLocalRoles(request);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
                ActorId.of(actorId), TenantId.of(tenantId), "demo-user", roles);
        // LOCAL has no display-name claim: the username is recorded.
        actorDisplayName.bind(principal.actorId(), null, principal.username());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, new Resolved(principal, null, principal.username()));
        return principal;
    }

    private AuthenticatedPrincipal resolveLocalIntegrationPrincipal(HttpServletRequest request) {
        UUID tenantId = resolveLocalUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveLocalUuid(request, HEADER_ACTOR_ID, demoActorId);
        String client = request.getHeader(HEADER_INTEGRATION_CLIENT);
        if (client == null || client.isBlank()) client = "demo-integration-client";
        return AuthenticatedPrincipal.of(ActorId.of(actorId), TenantId.of(tenantId), client.trim(), Set.of());
    }

    private AuthenticatedPrincipal resolveOidcIntegrationPrincipal(boolean scanner) {
        JwtAuthenticationToken token = requireOidcToken();
        var jwt = token.getToken();
        AuthenticatedIdentity identity = normalizeIdentity(jwt);
        requireMachine(jwt);
        if (!securityProperties.getOidc().isPersistentConnectionEnabled()
                || jwt.getIssuer() == null || securityProperties.getOidc().getAudience().isBlank()) {
            throw new SecurityContextException("Shared-issuer machine identity configuration is incomplete", "MACHINE_IDENTITY_CONFIGURATION_INCOMPLETE");
        }
        // The entity is the one whose enabled push (scanner route: scanner) connection names this client; no header chooses
        // it, and a client of the one kind is nobody on the other kind's routes.
        String provider = securityProperties.getOidc().getProviderId();
        java.net.URI issuer = java.net.URI.create(jwt.getIssuer().toString());
        String audience = securityProperties.getOidc().getAudience();
        var candidates = scanner
                ? machineTenantBindings.findEnabledScannerTenants(provider, issuer, audience, identity.username())
                : machineTenantBindings.findEnabledPushTenants(provider, issuer, audience, identity.username());
        TenantId tenantId = toTenant(refusingAsSecurityContext(() -> machineAdmission.requireUnique(toEntities(candidates))));
        ActorId actorId = trustedActorResolver.resolve(jwt);
        return AuthenticatedPrincipal.of(actorId, tenantId, identity.username(), Set.of());
    }

    /**
     * Task 0054 (platform-core 0.7.0, SEC-7): a machine path takes a machine's token only. A token that carries a value a
     * person's sign-in has (a name, an address, an interactive sign-in, groups; securedoc.security.oidc.machine-user-claims)
     * is refused, whatever user name it names. An empty value (such as an empty group list) marks nobody.
     */
    private void requireMachine(org.springframework.security.oauth2.jwt.Jwt jwt) {
        for (String claim : securityProperties.getOidc().getMachineUserClaims()) {
            Object value = jwt.getClaims().get(claim);
            if (value == null || value instanceof String text && text.isBlank()
                    || value instanceof java.util.Collection<?> list && list.isEmpty()) continue;
            throw new SecurityContextException("A person's sign-in cannot be used as an integration client",
                    "MACHINE_IDENTITY_IS_A_PERSON");
        }
    }

    private AuthenticatedPrincipal resolveOidcPrincipal(HttpServletRequest request) {
        JwtAuthenticationToken token = requireOidcToken();
        var jwt = token.getToken();
        AuthenticatedIdentity identity = normalizeIdentity(jwt);
        if (!securityProperties.getOidc().isPersistentConnectionEnabled()) {
            throw new SecurityContextException("Shared-issuer mode requires persisted OIDC connections", "SHARED_ISSUER_REQUIRES_PERSISTED_CONNECTIONS");
        }
        var authorized = oidcTenantMembershipResolver.authorizedTenants(jwt, identity.groups());
        if (authorized.isEmpty()) {
            SecurityContextException refused = new SecurityContextException(message(IdentityAdmissionException.Reason.NO_VERIFIED_MEMBERSHIP),
                    IdentityAdmissionException.Reason.NO_VERIFIED_MEMBERSHIP.name());
            // Task 0023 C5: with one entity it is clear whose log the refusal belongs in (the entity comes from the stored
            // connection, the person from the verified token). With several it belongs in none.
            throw oidcTenantMembershipResolver.onlyEntity(jwt).map(only -> attributed(refused, only, jwt)).orElse(refused);
        }
        TenantId tenantId = toTenant(refusingAsSecurityContext(
                () -> tenantSelection.select(request.getHeader("X-Selected-Tenant-Id"), toEntityKeys(authorized))));
        Set<String> roles = authorized.get(tenantId);
        ActorId actorId = trustedActorResolver.resolve(jwt);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(actorId, tenantId, identity.username(), roles);
        // The display-name claim (securedoc.security.oidc.display-name-claim), or the username when there is none.
        actorDisplayName.bind(actorId, identity.displayName(), identity.username());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, new Resolved(principal, identity.displayName(), identity.username()));
        return principal;
    }


    /**
     * The verified person of the current OIDC request, when there is one (task 0023 C5: to audit a refusal that
     * concerns them). Never throws.
     */
    public java.util.Optional<ActorId> currentOidcActor() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken token && authentication.isAuthenticated()) {
                return java.util.Optional.of(trustedActorResolver.resolve(token.getToken()));
            }
        } catch (RuntimeException ignored) {
            // No trusted actor: the caller records nothing in an entity's log.
        }
        return java.util.Optional.empty();
    }

    /** The issuer and the groups of the verified sign-in of the current request (task 0058). */
    public record VerifiedSignIn(java.net.URI issuer, Set<String> groups) {
    }

    /**
     * The verified sign-in of the current request: issuer and groups exactly as the normalized token has them. Empty in
     * LOCAL mode, where there is none.
     */
    public java.util.Optional<VerifiedSignIn> verifiedSignIn() {
        if (securityProperties.getMode() == SecurityMode.LOCAL) return java.util.Optional.empty();
        var jwt = requireOidcToken().getToken();
        if (jwt.getIssuer() == null) {
            throw new SecurityContextException("Authenticated identity has invalid OIDC identity claims", "INVALID_IDENTITY_CLAIMS");
        }
        return java.util.Optional.of(new VerifiedSignIn(java.net.URI.create(jwt.getIssuer().toString()),
                normalizeIdentity(jwt).groups()));
    }

    /**
     * The e-mail address of the verified sign-in (task 0059, for notifications only). Empty when there is none, or when the
     * login system says the person has not confirmed it. Gives no access.
     */
    public java.util.Optional<String> verifiedEmail() {
        if (securityProperties.getMode() == SecurityMode.LOCAL) return java.util.Optional.empty();
        var jwt = requireOidcToken().getToken();
        Object confirmed = jwt.getClaims().get("email_verified");
        if (confirmed != null && !"true".equalsIgnoreCase(confirmed.toString())) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(normalizeIdentity(jwt).email());
    }

    private SecurityContextException attributed(SecurityContextException refused, TenantId tenantId,
                                                org.springframework.security.oauth2.jwt.Jwt jwt) {
        try {
            return refused.attributedTo(tenantId, trustedActorResolver.resolve(jwt));
        } catch (SecurityContextException noActor) {
            return refused;
        }
    }

    private AuthenticatedIdentity normalizeIdentity(org.springframework.security.oauth2.jwt.Jwt jwt) {
        try {
            return oidcIdentityNormalizer.normalize(jwt);
        } catch (IllegalArgumentException ex) {
            throw new SecurityContextException("Authenticated identity has invalid OIDC identity claims", "INVALID_IDENTITY_CLAIMS");
        }
    }

    private JwtAuthenticationToken requireOidcToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new SecurityContextException("Authenticated OIDC identity is required", "OIDC_IDENTITY_REQUIRED");
        }
        return token;
    }

    private UUID resolveLocalUuid(HttpServletRequest request, String headerName, UUID fallback) {
        String header = request.getHeader(headerName);
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                throw new SecurityContextException("Invalid " + headerName + " header: must be a valid UUID", "INVALID_LOCAL_IDENTITY_HEADER");
            }
        }
        return fallback;
    }

    private Set<String> resolveLocalRoles(HttpServletRequest request) {
        String header = request.getHeader(HEADER_ACTOR_ROLES);
        if (header == null || header.isBlank()) {
            return Set.of(ProductRole.DOCUMENT_PROCESSOR.name());
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String raw : header.split(",")) {
            String role = raw.trim();
            if (role.isEmpty()) continue;
            ProductRole canonical = ProductRole.fromIdentityName(role)
                    .orElseThrow(() -> new SecurityContextException(
                            "Invalid role in X-Actor-Roles header: " + raw.trim(), "INVALID_LOCAL_ROLE_HEADER"));
            roles.add(canonical.name());
        }
        return roles.isEmpty() ? Set.of(ProductRole.DOCUMENT_PROCESSOR.name()) : Set.copyOf(roles);
    }
}
