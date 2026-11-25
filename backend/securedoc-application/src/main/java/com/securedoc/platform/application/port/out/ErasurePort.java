package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Task 0060: the only way stored files are erased. Each erasure is written to the erase log (outside the database) before
 * anything is erased, so that it is done again when the database or the storage is restored from a backup. When the log
 * can't be written nothing is erased (LV-SYS-014, or LV-SYS-013 before the replay) and the caller tries again later. The
 * erasing itself (the file, its protection record, the artifact's status, an audit record) happens here, in a transaction
 * of its own: an erasure is never undone by the caller's transaction.
 */
public interface ErasurePort {

    /** Who or what decided an erasure: a rule by its name, or a person by the actor id. Never a name or an address. */
    record Reason(String rule, ActorId person) {
        public Reason {
            Objects.requireNonNull(rule);
        }

        public static Reason rule(String rule) {
            return new Reason(rule, null);
        }

        /** The system actor is a rule, not a person. */
        public static Reason by(ActorId actor, String rule) {
            boolean system = actor == null || actor.value().getMostSignificantBits() == 0L
                    && actor.value().getLeastSignificantBits() == 0L;
            return new Reason(rule, system ? null : actor);
        }
    }

    /** Erases one stored file of a case. An artifact already deleted is left as it is. */
    void eraseArtifact(Artifact artifact, Reason reason);

    /** Erases every stored file of a case except its split copies, which have their own time. */
    void eraseCaseFiles(TenantId tenantId, PackageId packageId, Reason reason);

    /**
     * Task 0061: erases the case field values of business records no case needs any more (every case of the record purged).
     * Returns how many records.
     */
    int eraseUnusedCaseFields(int limit, Reason reason);
}
