package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;

import java.util.List;

/**
 * Inbound port for querying audit records. Always tenant-scoped.
 */
public interface ListAuditRecordsQuery {
    List<AuditRecordSummary> execute(AuditRecordFilter filter);
}
