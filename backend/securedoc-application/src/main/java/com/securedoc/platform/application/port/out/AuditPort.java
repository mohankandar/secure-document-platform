package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.audit.AuditRecord;

/**
 * Outbound port for persisting audit records.
 */
public interface AuditPort {

    /** Records within the caller's transaction, if there is one: the record is lost if that transaction rolls back. */
    void record(AuditRecord auditRecord);

    /**
     * Records in a transaction of its own, committed at once whatever happens to the caller's transaction (task 0023
     * C6). Only for refusal and failure records written just before the request fails (licence denial, a rejected
     * licence install), which must not be rolled back with it. The default, for stores without transactions, is
     * {@link #record}.
     */
    default void recordIndependently(AuditRecord auditRecord) {
        record(auditRecord);
    }
}
