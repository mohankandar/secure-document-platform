package com.securedoc.platform.application.intake;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CaseInstructionsRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TransactionPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0065: a case's instructions for the AI (the parties and whose names to protect), from the source system or a
 * reviewer. Document text: one file in the case's own folder of the processing storage, encrypted like its documents and
 * erased with the case's files; never in a log, an audit record or a report. Refused while the entity's setting is off.
 * A change holds from the next search of the case's documents.
 */
public final class CaseInstructionsService {
    public static final int MAX_CHARACTERS = 2_000;
    private static final Logger log = LoggerFactory.getLogger(CaseInstructionsService.class);
    private static final Set<PackageStatus> CHANGEABLE = EnumSet.of(PackageStatus.CREATED, PackageStatus.PENDING_REVIEW,
            PackageStatus.IN_REVIEW, PackageStatus.PENDING_FINAL_APPROVAL, PackageStatus.COMPLETED, PackageStatus.FAILED);

    /** The text, and an id that changes with it. */
    public record Instructions(UUID id, String text) {
        /** Never the text. */
        @Override
        public String toString() {
            return "Instructions[" + id + "]";
        }
    }

    private final CaseInstructionsRepository repository;
    private final StoragePort storage;
    private final PackageRepository packages;
    private final TenantRedactionPolicyRepository policies;
    private final com.securedoc.platform.application.port.out.PackageLifecycleRepository lifecycles;
    private final TransactionPort transactions;
    private final AuditService audit;

    public CaseInstructionsService(CaseInstructionsRepository repository, StoragePort storage, PackageRepository packages,
                                   TenantRedactionPolicyRepository policies,
                                   com.securedoc.platform.application.port.out.PackageLifecycleRepository lifecycles,
                                   TransactionPort transactions, AuditService audit) {
        this.repository = Objects.requireNonNull(repository);
        this.storage = Objects.requireNonNull(storage);
        this.packages = Objects.requireNonNull(packages);
        this.policies = Objects.requireNonNull(policies);
        this.lifecycles = Objects.requireNonNull(lifecycles);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
    }

    /** Null or blank: none. Checked before anything of the case is made. */
    public void check(TenantId tenantId, String sent) {
        if (sent != null && !sent.isBlank()) required(tenantId, sent);
    }

    /** Of a case being made, in its transaction. Null or blank: none. */
    public void atIntake(TenantId tenantId, ActorId actorId, PackageId packageId, String sent, CorrelationId correlationId) {
        if (sent == null || sent.isBlank()) return;
        String text = required(tenantId, sent);
        repository.save(tenantId, packageId, store(tenantId, packageId, text));
        recordChange(tenantId, actorId, packageId, text.length(), correlationId);
    }

    /** Blank removes them. */
    public void change(TenantId tenantId, ActorId actorId, PackageId packageId, String sent, CorrelationId correlationId) {
        var pkg = packages.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        boolean purged = lifecycles.findByPackageIdAndTenantId(packageId, tenantId)
                .map(l -> l.purgedAt().isPresent()).orElse(false);
        if (purged || !CHANGEABLE.contains(pkg.status())) {
            throw new ValidationException(LvErrorCode.REV_002, "The case's instructions can't be changed now");
        }
        String text = sent == null || sent.isBlank() ? null : required(tenantId, sent);
        Optional<Instructions> before = find(tenantId, packageId);
        if (before.map(Instructions::text).equals(Optional.ofNullable(text))) return;
        Optional<CaseInstructionsRepository.Stored> old = repository.find(tenantId, packageId);
        CaseInstructionsRepository.Stored made = text == null ? null : store(tenantId, packageId, text);
        try {
            // Saved by itself: the old file goes only once nothing points to it.
            transactions.inNewTransaction(() -> {
                if (made == null) repository.remove(tenantId, packageId);
                else repository.save(tenantId, packageId, made);
                recordChange(tenantId, actorId, packageId, text == null ? 0 : text.length(), correlationId);
                return null;
            });
        } catch (RuntimeException notSaved) {
            if (made != null) delete(packageId, made.reference());
            throw notSaved;
        }
        old.ifPresent(stored -> delete(packageId, stored.reference()));
    }

    public Optional<Instructions> find(TenantId tenantId, PackageId packageId) {
        return repository.find(tenantId, packageId).map(stored -> {
            try (InputStream in = storage.retrieve(stored.reference())) {
                return new Instructions(stored.fileId(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("The case's instructions could not be read", e);
            }
        });
    }

    /** Whether a source that sends its case again sends other instructions than are kept (none is the same as blank). */
    public boolean differs(TenantId tenantId, PackageId packageId, String sent) {
        return !find(tenantId, packageId).map(Instructions::text).orElse("").equals(sent == null ? "" : clean(sent));
    }

    private String required(TenantId tenantId, String sent) {
        if (!policies.findByTenantId(tenantId).map(TenantRedactionPolicy::documentContext).orElse(false)) {
            throw new ValidationException(LvErrorCode.CFG_001, "Case instructions are not switched on for this entity");
        }
        String text = clean(sent);
        if (text.isEmpty() || text.length() > MAX_CHARACTERS) {
            throw new ValidationException(LvErrorCode.INT_003,
                    "Case instructions must have between 1 and " + MAX_CHARACTERS + " characters");
        }
        return text;
    }

    /** Line breaks stay; other control characters and the marks that frame pages in a prompt do not. */
    private static String clean(String text) {
        return text.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n]]", " ").replace('<', ' ').replace('>', ' ').strip();
    }

    private CaseInstructionsRepository.Stored store(TenantId tenantId, PackageId packageId, String text) {
        UUID id = UUID.randomUUID();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new CaseInstructionsRepository.Stored(id, storage.storeForPackage(tenantId, packageId,
                tenantId + "/" + packageId + "/CASE_INSTRUCTIONS/" + id, new ByteArrayInputStream(bytes), bytes.length,
                "text/plain"));
    }

    private void delete(PackageId packageId, ArtifactStorageReference reference) {
        try {
            storage.delete(reference);
        } catch (RuntimeException e) {
            // It stays encrypted in the case's folder and goes with the case's files.
            log.warn("CASE_INSTRUCTIONS_FILE_NOT_DELETED package={} type={}", packageId, e.getClass().getSimpleName());
        }
    }

    private void recordChange(TenantId tenantId, ActorId actorId, PackageId packageId, int characters,
                              CorrelationId correlationId) {
        audit.recordSuccess(tenantId, actorId, AuditAction.CASE_INSTRUCTIONS_CHANGED, "DocumentPackage",
                packageId.toString(), packageId, correlationId, "characters=" + characters);
    }
}
