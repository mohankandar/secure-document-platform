package com.securedoc.platform.api.identity;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.infrastructure.identity.JdbcOidcConnectionBindingRepository;
import com.securedoc.platform.infrastructure.identity.JdbcOidcConnectionBindingRepository.ConnectionAdminView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/enterprise-identity")
public class EnterpriseIdentityAdminController {
    private final RequestContextResolver context; private final JdbcOidcConnectionBindingRepository repository; private final OidcDiscoveryTester tester; private final AuditService audit;
    public EnterpriseIdentityAdminController(RequestContextResolver context,JdbcOidcConnectionBindingRepository repository,OidcDiscoveryTester tester,AuditService audit){this.context=context;this.repository=repository;this.tester=tester;this.audit=audit;}

    @GetMapping @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> overview(HttpServletRequest request){var p=context.resolvePrincipal(request);return ResponseEntity.ok(overviewFor(p.tenantId().value()));}

    @PutMapping("/draft") @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> saveDraft(@RequestBody DraftRequest body,HttpServletRequest request){if(body==null)throw new IllegalArgumentException("Draft configuration is required.");var p=context.resolvePrincipal(request);ConnectionAdminView saved=repository.saveDraft(p.tenantId().value(),defaultProvider(body.providerId()),body.displayName(),parseIssuer(body.issuerUri()),body.browserClientId(),body.apiAudience(),p.username());audit.recordSuccess(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED,"EnterpriseIdentityConnection",saved.connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity draft saved: version="+saved.configurationVersion());return ResponseEntity.ok(overviewFor(p.tenantId().value()));}

    @PostMapping("/draft/test") @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> testDraft(HttpServletRequest request){var p=context.resolvePrincipal(request);ConnectionAdminView staged=repository.findStagedForTenant(p.tenantId().value()).orElseThrow(()->new IllegalArgumentException("Save a draft identity configuration before testing it."));OidcDiscoveryTester.TestResult result=tester.test(staged.issuerUri());ConnectionAdminView tested=repository.recordTest(p.tenantId().value(),staged.connectionId(),staged.configurationVersion(),result.passed(),result.message(),p.username());if(result.passed())audit.recordSuccess(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,"EnterpriseIdentityConnection",tested.connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity draft tested: version="+tested.configurationVersion()+", result=PASSED");else audit.recordFailure(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,"EnterpriseIdentityConnection",tested.connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity draft tested: version="+tested.configurationVersion()+", result=FAILED");return ResponseEntity.ok(overviewFor(p.tenantId().value()));}

    @PostMapping("/draft/activate") @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> activateDraft(@RequestBody ActivationRequest body,HttpServletRequest request){
        if(body==null||!"ACTIVATE".equals(body.confirmation()))throw new IllegalArgumentException("Activation requires the exact confirmation value ACTIVATE.");
        var p=context.resolvePrincipal(request); ConnectionAdminView staged=repository.findStagedForTenant(p.tenantId().value()).orElseThrow(()->new IllegalArgumentException("No staged identity configuration is available to activate."));
        if(!staged.connectionId().equals(body.connectionId()))throw new IllegalStateException("Activation target is not the current staged identity configuration.");
        try{
            var result=repository.activateValidated(p.tenantId().value(),body.connectionId(),body.expectedConfigurationVersion(),p.username());
            audit.recordSuccess(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED,"EnterpriseIdentityConnection",result.active().connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity connection activated: newVersion="+result.active().configurationVersion()+", previousConnection="+result.previousActive().connectionId()+", previousVersion="+result.previousActive().configurationVersion());
            return ResponseEntity.ok(overviewFor(p.tenantId().value()));
        }catch(RuntimeException ex){
            audit.recordFailure(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED,"EnterpriseIdentityConnection",body.connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity activation rejected: version="+body.expectedConfigurationVersion());
            throw ex;
        }
    }

    @PostMapping("/rollback") @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> rollback(@RequestBody RollbackRequest body,HttpServletRequest request){
        if(body==null||!"ROLLBACK".equals(body.confirmation()))throw new IllegalArgumentException("Rollback requires the exact confirmation value ROLLBACK.");
        var p=context.resolvePrincipal(request);
        try{
            var result=repository.rollbackToKnownGood(p.tenantId().value(),body.connectionId(),body.expectedConfigurationVersion(),p.username());
            audit.recordSuccess(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK,"EnterpriseIdentityConnection",result.active().connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity connection rolled back: restoredVersion="+result.active().configurationVersion()+", replacedConnection="+result.previousActive().connectionId()+", replacedVersion="+result.previousActive().configurationVersion());
            return ResponseEntity.ok(overviewFor(p.tenantId().value()));
        }catch(RuntimeException ex){
            audit.recordFailure(p.tenantId(),p.actorId(),AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED,"EnterpriseIdentityConnection",body.connectionId()==null?"UNKNOWN":body.connectionId().toString(),context.resolveCorrelationId(request),"Enterprise identity rollback rejected: version="+body.expectedConfigurationVersion());
            throw ex;
        }
    }

    private Overview overviewFor(UUID tenantId){return new Overview(repository.findActiveForTenant(tenantId).orElse(null),repository.findStagedForTenant(tenantId).orElse(null),repository.findRollbackCandidatesForTenant(tenantId),true,900);}
    private static URI parseIssuer(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Issuer URI is required.");return URI.create(value.trim());}
    private static String defaultProvider(String value){return value==null||value.isBlank()?"customer-oidc":value.trim();}
    public record DraftRequest(String providerId,String displayName,String issuerUri,String browserClientId,String apiAudience){}
    public record ActivationRequest(UUID connectionId,long expectedConfigurationVersion,String confirmation){}
    public record RollbackRequest(UUID connectionId,long expectedConfigurationVersion,String confirmation){}
    public record Overview(ConnectionAdminView active,ConnectionAdminView staged,List<ConnectionAdminView> rollbackCandidates,boolean activationSupported,long activationMaxTestAgeSeconds){}
}
