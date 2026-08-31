package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;

import java.util.List;

/**
 * Outbound port for querying persisted audit records.
 * All queries must be tenant-scoped.
 */
public interface AuditQueryPort {
    List<AuditRecordSummary> findByFilter(AuditRecordFilter filter);
}
