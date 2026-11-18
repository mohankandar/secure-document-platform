package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.mapping.MappingError;
import com.documentplatform.identity.mapping.MappingPlan;
import com.documentplatform.identity.mapping.MappingRow;
import com.documentplatform.identity.mapping.RoleMappingFile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.RoleMappingStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Loads a role mapping file into {@code tenant_oidc_group_grants}. The whole file is validated first; a refused file
 * writes nothing. For each entity the file names, the grants become exactly the file's rows; other entities are not
 * touched. All of it runs in one transaction. Loading the same file twice writes nothing and records nothing.
 */
@Service
public class RoleMappingLoader {

    public static final String FORMAT_ID = "lexavault-role-mapping/1";
    static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    /** What a load did. {@code errors} non-empty means the file was refused and nothing was written. */
    public record Outcome(List<MappingError> errors, MappingPlan plan, boolean written, String sha256) {
        public boolean refused() {
            return !errors.isEmpty();
        }
    }

    private final RoleMappingStore store;
    private final AuditPort audit;
    private final ObjectMapper json;
    private final ProductEntityRoleCatalog roles = new ProductEntityRoleCatalog();

    public RoleMappingLoader(RoleMappingStore store, AuditPort audit, ObjectMapper json) {
        this.store = Objects.requireNonNull(store);
        this.audit = Objects.requireNonNull(audit);
        this.json = Objects.requireNonNull(json);
    }

    @Transactional
    public Outcome load(byte[] content, String fileName, String osUser, boolean dryRun) {
        String sha256 = sha256(content);
        RoleMappingFile reader = new RoleMappingFile(FORMAT_ID, roles, store);
        RoleMappingFile.Result first = reader.read(content);
        if (!first.valid()) return new Outcome(first.errors(), null, false, sha256);

        // Lock the named entities in a fixed order, then check again under the lock.
        List<EntityId> entities = first.rows().stream().map(MappingRow::entity).distinct()
                .sorted((a, b) -> a.value().compareTo(b.value())).toList();
        entities.forEach(store::lock);
        RoleMappingFile.Result result = reader.read(content);
        if (!result.valid()) return new Outcome(result.errors(), null, false, sha256);

        Map<EntityId, String> issuers = new LinkedHashMap<>();
        for (EntityId entity : entities) {
            issuers.put(entity, store.activeIssuer(entity).orElseThrow(() ->
                    new IllegalStateException("Tenant " + entity + " lost its ACTIVE login-system record during the load")));
        }
        MappingPlan plan = MappingPlan.between(result.rows(), e -> store.grants(e, issuers.get(e)));
        if (dryRun || !plan.hasChanges()) return new Outcome(List.of(), plan, false, sha256);

        CorrelationId correlation = CorrelationId.generate();
        for (MappingPlan.EntityChange change : plan.entities()) {
            if (!change.changes()) continue;
            String issuer = issuers.get(change.entity());
            for (Grant grant : change.toRemove()) store.remove(change.entity(), issuer, grant);
            for (Grant grant : change.toAdd()) store.add(change.entity(), issuer, grant);
            audit.record(AuditRecord.create(TenantId.of(change.entity().value()), SYSTEM_ACTOR,
                    AuditAction.ROLE_MAPPING_LOADED, "TENANT_ROLE_MAPPING", change.entity().toString(),
                    AuditOutcome.SUCCESS, correlation, detail(change, sha256, fileName, osUser)));
        }
        return new Outcome(List.of(), plan, true, sha256);
    }

    private String detail(MappingPlan.EntityChange change, String sha256, String fileName, String osUser) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("added", change.toAdd().size());
        detail.put("removed", change.toRemove().size());
        detail.put("unchanged", change.unchanged());
        detail.put("fileSha256", sha256);
        detail.put("fileName", fileName);
        detail.put("osUser", osUser);
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit detail could not be written", e);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
