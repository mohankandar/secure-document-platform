package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ErasurePort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Task 0060: every erasure through platform-core's erase log. The handlers of this package do the erasing. */
@Component
public class LexaVaultErasures implements ErasurePort {
    private static final Logger log = LoggerFactory.getLogger(LexaVaultErasures.class);

    private final Eraser eraser;
    private final JdbcTemplate jdbc;

    public LexaVaultErasures(Eraser eraser, JdbcTemplate jdbc) {
        this.eraser = Objects.requireNonNull(eraser);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void eraseArtifact(Artifact artifact, Reason reason) {
        if (artifact.status() == ArtifactStatus.DELETED) return;
        UUID packageId = jdbc.query("select package_id from documents where id = ? and tenant_id = ?",
                        (rs, n) -> rs.getObject(1, UUID.class), artifact.documentId().value(), artifact.tenantId().value())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("The artifact's document is not known"));
        erase(artifact.tenantId(), EraseRecord.kind(artifact.artifactType()), EraseRecord.FILE,
                new EraseRecord.FileId(packageId, artifact.documentId().value(), artifact.id().value()).toString(), reason);
    }

    @Override
    public void eraseCaseFiles(TenantId tenantId, PackageId packageId, Reason reason) {
        erase(tenantId, EraseRecord.CASE_FILES, EraseRecord.CASE, packageId.value().toString(), reason);
    }

    /** A file no record points to (the sweep). */
    public void eraseUnrecorded(TenantId tenantId, UUID packageId, UUID documentId, UUID fileId, Reason reason) {
        erase(tenantId, EraseRecord.UNRECORDED_FILE, EraseRecord.FILE,
                new EraseRecord.FileId(packageId, documentId, fileId).toString(), reason);
    }

    private void erase(TenantId tenantId, String kind, String recordType, String recordId, Reason reason) {
        // The audit record is written with the erasure, under this id (also when the erasure is replayed after a restore).
        UUID auditId = UUID.randomUUID();
        ErasedBy by = reason.person() == null ? ErasedBy.rule(reason.rule())
                : ErasedBy.person(reason.person().value().toString());
        try {
            eraser.erase(new EraseRequest(tenantId.value(), EraseRecord.PRODUCT, recordType, recordId, kind, Instant.now(),
                    by, auditId.toString()));
        } catch (EraseLogException refused) {
            throw failure(refused, kind);
        }
    }

    private static TechnicalDependencyException failure(EraseLogException e, String kind) {
        String handler = e.handlerCode().orElse(null);
        log.warn("ERASURE_NOT_DONE kind={} code={} handlerCode={}", kind, e.code().name(), handler);
        TechnicalFailureCode code;
        TechnicalDependency dependency = TechnicalDependency.ERASE_LOG;
        if (e.code() == EraseLogError.ERASE_LOG_RESTORE_DETECTED) {
            code = TechnicalFailureCode.ERASE_REPLAY_REQUIRED;
        } else if (StoredFileErasure.STORAGE_UNAVAILABLE.equals(handler)) {
            code = TechnicalFailureCode.STORAGE_IO;
            dependency = TechnicalDependency.STORAGE;
        } else {
            code = TechnicalFailureCode.ERASE_LOG_UNAVAILABLE;
        }
        return new TechnicalDependencyException(dependency, code, true, "Erasure not done: " + e.code().name(), e);
    }
}
