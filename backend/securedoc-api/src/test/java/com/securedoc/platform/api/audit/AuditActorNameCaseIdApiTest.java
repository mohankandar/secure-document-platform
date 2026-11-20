package com.securedoc.platform.api.audit;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.AuditController;
import com.securedoc.platform.api.observability.RequestActorDisplayNameFilter;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
import com.securedoc.platform.api.security.oidc.OidcTenantResolver;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0016 A1/A6 at the API boundary: the display name is bound where the request's principal is resolved, and
 * both new fields come out of the audit API and the export. Old rows read back with empty values.
 */
class AuditActorNameCaseIdApiTest {

    private final RequestActorDisplayName names = new RequestActorDisplayName();
    private final List<AuditRecord> records = new ArrayList<>();
    private final AuditService audit = new AuditService(records::add, names);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        names.clear();
    }

    @Test
    void oidcPersonWithANameClaimIsRecordedByThatName() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt("Dana Reviewer")));
        AuthenticatedPrincipal principal = resolver(oidcProperties()).resolvePrincipal(mock(HttpServletRequest.class));

        recordCaseAction(principal.actorId());
        assertEquals("Dana Reviewer", records.getLast().actorDisplayName());
    }

    @Test
    void oidcPersonWithoutANameClaimIsRecordedByUsername() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt(null)));
        AuthenticatedPrincipal principal = resolver(oidcProperties()).resolvePrincipal(mock(HttpServletRequest.class));

        recordCaseAction(principal.actorId());
        assertEquals("person@example.test", records.getLast().actorDisplayName());
    }

    @Test
    void configuredDisplayNameClaimIsTheOneRead() {
        SecurityProperties properties = oidcProperties();
        properties.getOidc().setDisplayNameClaim("display_name");
        Jwt token = baseJwt().claim("name", "Not This").claim("display_name", "Dana R.").build();
        SecurityContextHolder.getContext().setAuthentication(authenticated(token));
        AuthenticatedPrincipal principal = resolver(properties).resolvePrincipal(mock(HttpServletRequest.class));

        recordCaseAction(principal.actorId());
        assertEquals("Dana R.", records.getLast().actorDisplayName());
    }

    @Test
    void localModeRecordsTheUsername() {
        SecurityProperties local = new SecurityProperties();
        local.setMode(SecurityMode.LOCAL);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenReturn(null);
        AuthenticatedPrincipal principal = resolver(local).resolvePrincipal(request);

        recordCaseAction(principal.actorId());
        assertEquals("demo-user", records.getLast().actorDisplayName());
    }

    @Test
    void machineClientsAndSystemWorkRecordNoName() {
        SecurityProperties local = new SecurityProperties();
        local.setMode(SecurityMode.LOCAL);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenReturn(null);
        RequestContextResolver resolver = resolver(local);
        AuthenticatedPrincipal person = resolver.resolvePrincipal(request);

        recordCaseAction(ActorId.of(RequestActorDisplayName.SYSTEM_ACTOR_ID));
        assertNull(records.getLast().actorDisplayName(), "system work inside a person's request");

        AuthenticatedPrincipal machine = resolver.resolveIntegrationPrincipal(request);
        assertEquals(person.actorId(), machine.actorId(), "LOCAL demo ids are shared, so only clearing protects");
        recordCaseAction(machine.actorId());
        assertNull(records.getLast().actorDisplayName(), "a machine client is not a person");
    }

    @Test
    void theFilterClearsTheNameWhenTheRequestEnds() throws Exception {
        ActorId person = ActorId.generate();
        AtomicReference<String> during = new AtomicReference<>();
        new RequestActorDisplayNameFilter(names).doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class),
                (req, res) -> {
                    names.bind(person, "Dana Reviewer", "dreviewer");
                    during.set(names.displayNameFor(person).orElse(null));
                });
        assertEquals("Dana Reviewer", during.get());
        assertTrue(names.displayNameFor(person).isEmpty(), "nothing left on the pooled thread");
    }

    @Test
    void caseActionRecordsTheCaseIdAndTheApiAndExportShowBothFields() {
        UUID packageId = UUID.randomUUID();
        ActorId person = ActorId.generate();
        names.bind(person, "Dana \"D\" Reviewer", "dreviewer");
        audit.recordSuccess(TenantId.generate(), person, AuditAction.PACKAGE_APPROVED, "DocumentPackage",
                packageId.toString(), CorrelationId.generate(), "Package approved");
        AuditRecord saved = records.getLast();
        assertEquals(packageId.toString(), saved.caseId());

        AuditRecordSummary newRow = new AuditRecordSummary(saved.id().toString(), saved.actorId().toString(),
                saved.action().name(), saved.resourceType(), saved.resourceId(), saved.outcome().name(),
                saved.correlationId().toString(), saved.detail(), saved.timestamp(), saved.actorDisplayName(), saved.caseId());
        AuditRecordSummary oldRow = new AuditRecordSummary(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PACKAGE_CREATED", "DocumentPackage", packageId.toString(), "SUCCESS", UUID.randomUUID().toString(),
                null, Instant.parse("2026-11-01T10:00:00Z"));
        AuditController controller = controller(List.of(newRow, oldRow));
        HttpServletRequest request = mock(HttpServletRequest.class);

        List<Map<String, Object>> listed = controller.listAuditRecords(null, null, null, null, null, null, null,
                null, null, 50, false, request).getBody();
        assertEquals("Dana \"D\" Reviewer", listed.get(0).get("actorDisplayName"));
        assertEquals(packageId.toString(), listed.get(0).get("caseId"));
        assertTrue(listed.get(1).containsKey("actorDisplayName") && listed.get(1).get("actorDisplayName") == null,
                "an old row reads back with an empty display name");
        assertTrue(listed.get(1).containsKey("caseId") && listed.get(1).get("caseId") == null,
                "an old row reads back with an empty case ID");
        assertEquals(List.of("id", "actorId", "action", "resourceType", "resourceId", "outcome", "correlationId",
                "detail", "occurredAt", "actorDisplayName", "caseId"), List.copyOf(listed.get(0).keySet()),
                "existing fields unchanged and first; new fields appended");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> json = (List<Map<String, Object>>) export(controller, "json").getBody();
        assertEquals("Dana \"D\" Reviewer", json.get(0).get("actorDisplayName"));
        assertNull(json.get(1).get("caseId"));

        String csv = (String) export(controller, "csv").getBody();
        String[] lines = csv.split("\r\n");
        assertEquals("occurredAt,action,outcome,actorId,resourceType,resourceId,correlationId,detail,actorDisplayName,caseId",
                lines[0]);
        assertTrue(lines[1].endsWith(",\"Package approved\",\"Dana \"\"D\"\" Reviewer\",\"" + packageId + "\""), lines[1]);
        assertTrue(lines[2].endsWith(",,,"), "old row: empty detail, display name and case ID: " + lines[2]);
    }

    private ResponseEntity<?> export(AuditController controller, String format) {
        return controller.exportAuditRecords(format, null, null, null, null, null, null, null, null, null, false,
                mock(HttpServletRequest.class));
    }

    private AuditController controller(List<AuditRecordSummary> rows) {
        ListAuditRecordsQuery query = mock(ListAuditRecordsQuery.class);
        when(query.execute(any())).thenReturn(rows);
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(),
                TenantId.generate(), "audit-admin", Set.of("TENANT_IT_ADMIN")));
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        return new AuditController(query, resolver, mock(AuditService.class));
    }

    private void recordCaseAction(ActorId actorId) {
        audit.recordSuccess(TenantId.generate(), actorId, AuditAction.PACKAGE_APPROVED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), null);
    }

    private RequestContextResolver resolver(SecurityProperties properties) {
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                properties,
                new OidcRoleMapper(properties),
                new OidcTenantResolver(properties, persistedConnectionResolver(properties)),
                new OidcTenantMembershipResolver(mock(TenantOidcGroupGrantRepository.class),
                        mock(ActiveOidcTenantCandidates.class), properties),
                mock(ActiveOidcMachineTenantBindings.class),
                new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer(
                        properties.getOidc().getProviderId(),
                        new IdentityClaimConfig(
                                properties.getOidc().getUsernameClaim(),
                                properties.getOidc().getEmailClaim(),
                                properties.getOidc().getDisplayNameClaim(),
                                properties.getOidc().getRolesClaim())),
                names);
    }

    private OidcConnectionBindingResolver persistedConnectionResolver(SecurityProperties properties) {
        return (providerId, issuer, audience) -> {
            if (!properties.getOidc().getProviderId().equals(providerId)
                    || !URI.create("https://identity.example.test").equals(issuer)
                    || !properties.getOidc().getAudience().equals(audience)) {
                return Optional.empty();
            }
            return Optional.of(new OidcConnectionBinding(
                    UUID.fromString("00000000-0000-0000-0000-000000000010"),
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    providerId, issuer, audience, 1));
        };
    }

    private SecurityProperties oidcProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setAudience("lexavault-api");
        properties.getOidc().setTenantId("00000000-0000-0000-0000-000000000001");
        properties.getOidc().getRoleMappings().put("DOCUMENT_PROCESSOR", "securedoc-document-processor");
        return properties;
    }

    private JwtAuthenticationToken authenticated(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED")));
    }

    private Jwt jwt(String name) {
        Jwt.Builder builder = baseJwt();
        if (name != null) builder.claim("name", name);
        return builder.build();
    }

    private Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .issuer("https://identity.example.test")
                .subject("subject-123")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", "person@example.test")
                .claim("groups", List.of("securedoc-document-processor"));
    }
}
