package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.audit.AuditRecord;

/**
 * Outbound port for persisting audit records.
 */
public interface AuditPort {

    void record(AuditRecord auditRecord);
}
