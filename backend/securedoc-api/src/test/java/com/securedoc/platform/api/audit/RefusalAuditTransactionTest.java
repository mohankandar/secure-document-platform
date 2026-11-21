package com.securedoc.platform.api.audit;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.PackageController;
import com.securedoc.platform.api.dto.CreatePackageRequest;
import com.securedoc.platform.api.intake.external.ExternalIntakeController;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.IntakeExpiredException;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.application.licensing.LicenseTokenVerifier;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import com.securedoc.platform.domain.licensing.LicenseVerificationResult;
import com.securedoc.platform.application.licensing.TenantLicenseRepository;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.infrastructure.audit.JpaAuditPortAdapter;
import com.securedoc.platform.infrastructure.persistence.entity.AuditRecordEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataAuditRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Task 0023 C6 (#308, #28): does an audit row written just before an exception on a {@code @Transactional} request
 * survive the request's rollback?
 *
 * <p>No database is used. The real {@link JpaAuditPortAdapter} writes through a repository stand-in that behaves as
 * Spring Data does: a save inside a transaction is kept only when that transaction commits, a save outside one is
 * kept at once. The request boundary is the real controller method behind Spring's own
 * {@link TransactionInterceptor} with its {@code @Transactional} attributes, so the test sees the same commit or
 * rollback decision the running app makes.
 */
class RefusalAuditTransactionTest {

    private final List<AuditRecordEntity> committed = new ArrayList<>();
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();

    @Test
    void aLicenceDenialAuditSurvivesTheRequestRollback() {
        Tenant tenant = Tenant.create("Licence Entity", "licence-entity");
        AuditService audit = new AuditService(auditAdapter());
        TenantLicensingService licensing = licensingWithoutLicence(tenant, audit);
        CreatePackageUseCase create = mock(CreatePackageUseCase.class);
        when(create.execute(any(), any())).thenAnswer(call -> {
            licensing.requireDocumentProcessing(tenant.id(), ActorId.generate(), CorrelationId.generate());
            return null;
        });
        PackageController controller = transactional(new PackageController(create, mock(UploadDocumentUseCase.class),
                mock(SubmitPackageUseCase.class), mock(PackageProcessingDispatcher.class), mock(GetPackageQuery.class),
                mock(ListPackagesQuery.class), context(tenant.id())));

        assertThrows(LicenseEnforcementException.class, () -> controller.createPackage(
                new CreatePackageRequest("MANUAL_UPLOAD", null, null), new MockHttpServletRequest()));

        assertEquals(1, transactions.rollbacks, "the request itself is rolled back");
        assertEquals(List.of(AuditAction.LICENSE_ENFORCEMENT_DENIED.name()), committedActions(),
                "the denial audit row is kept although the request rolled back");
    }

    @Test
    void anInRequestIntakeExpiryIsKeptWithItsAudit() throws Exception {
        TenantId tenantId = TenantId.generate();
        AuditService audit = new AuditService(auditAdapter());
        ExternalIntakeService intake = mock(ExternalIntakeService.class);
        when(intake.receivePushDocument(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(call -> {
                    // What ExternalIntakeMaintenanceService.expireIfDue writes before the service throws 410.
                    audit.recordSuccess(tenantId, ActorId.generate(), AuditAction.EXTERNAL_INTAKE_EXPIRED,
                            "ExternalIntake", UUID.randomUUID().toString(), CorrelationId.generate(), "expired");
                    throw new IntakeExpiredException("expired");
                });
        ExternalIntakeController controller = transactional(new ExternalIntakeController(intake, context(tenantId)));

        assertThrows(IntakeExpiredException.class, () -> controller.uploadDocument(UUID.randomUUID(), "D-1", "key-1",
                "a".repeat(64), new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}),
                new MockHttpServletRequest()));

        assertEquals(0, transactions.rollbacks, "a 410 commits the expiry it recorded");
        assertEquals(List.of(AuditAction.EXTERNAL_INTAKE_EXPIRED.name()), committedActions());
    }

    @Test
    void aScannerUploadExpiryIsKeptWithItsAudit() {
        TenantId tenantId = TenantId.generate();
        AuditService audit = new AuditService(auditAdapter());
        ExternalIntakeService intake = mock(ExternalIntakeService.class);
        when(intake.receiveScannerDocument(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    audit.recordSuccess(tenantId, ActorId.generate(), AuditAction.EXTERNAL_INTAKE_EXPIRED,
                            "ExternalIntake", UUID.randomUUID().toString(), CorrelationId.generate(), "expired");
                    throw new IntakeExpiredException("expired");
                });
        var boundary = transactional(
                new com.securedoc.platform.api.intake.external.ScannerIntakeTransactionBoundary(intake));

        assertThrows(IntakeExpiredException.class, () -> boundary.receive(null, "D-1", "k", "s", "application/pdf",
                null, null, CorrelationId.generate()));

        assertEquals(0, transactions.rollbacks);
        assertEquals(List.of(AuditAction.EXTERNAL_INTAKE_EXPIRED.name()), committedActions());
    }

    @Test
    void anOrdinaryAuditStillJoinsTheRequestAndRollsBackWithIt() {
        // The independent write is only for refusal and failure audits; everything else keeps today's behaviour.
        TenantId tenantId = TenantId.generate();
        AuditService audit = new AuditService(auditAdapter());
        var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
            audit.recordSuccess(tenantId, ActorId.generate(), AuditAction.PACKAGE_CREATED, "Package", "p",
                    CorrelationId.generate(), "created");
            throw new IllegalStateException("later failure");
        }));
        assertTrue(committedActions().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private JpaAuditPortAdapter auditAdapter() {
        SpringDataAuditRecordRepository repository = mock(SpringDataAuditRecordRepository.class);
        when(repository.save(any(AuditRecordEntity.class))).thenAnswer(call -> {
            AuditRecordEntity entity = call.getArgument(0);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { committed.add(entity); }
                });
            } else {
                committed.add(entity);
            }
            return entity;
        });
        return new JpaAuditPortAdapter(repository, transactions);
    }

    private List<String> committedActions() {
        return committed.stream().map(AuditRecordEntity::getAction).toList();
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static RequestContextResolver context(TenantId tenantId) {
        RequestContextResolver context = mock(RequestContextResolver.class);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenantId, "person",
                Set.of("DOCUMENT_PROCESSOR"));
        when(context.resolvePrincipal(any(HttpServletRequest.class))).thenReturn(principal);
        when(context.resolveIntegrationPrincipal(any(HttpServletRequest.class))).thenReturn(principal);
        when(context.resolveCorrelationId(any(HttpServletRequest.class))).thenReturn(CorrelationId.generate());
        return context;
    }

    private static TenantLicensingService licensingWithoutLicence(Tenant tenant, AuditService audit) {
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(tenant.id())).thenReturn(Optional.of(tenant));
        TenantLicenseRepository licences = mock(TenantLicenseRepository.class);
        when(licences.findActive(tenant.id())).thenReturn(Optional.empty());
        LicenseTokenVerifier verifier = new LicenseTokenVerifier() {
            @Override public LicenseVerificationResult verify(String token, TenantId expected, Instant now) {
                return LicenseVerificationResult.of(LicenseValidationStatus.MISSING, null, "LICENSE_MISSING");
            }
            @Override public boolean isVerificationKeyConfigured() { return true; }
        };
        return new TenantLicensingService(licences, tenants, verifier, audit, true);
    }

    /** A transaction manager with Spring's real propagation, suspension and synchronization; it only counts. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected Object doSuspend(Object transaction) { return transaction; }
        @Override protected void doResume(Object transaction, Object suspendedResources) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
