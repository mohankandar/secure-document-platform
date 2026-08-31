package com.securedoc.platform.application.audit.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import com.securedoc.platform.application.port.out.AuditQueryPort;

import java.util.List;
import java.util.Objects;

/**
 * Application service for querying audit records.
 * Always requires tenant context — does not allow cross-tenant queries.
 */
public final class AuditQueryService implements ListAuditRecordsQuery {

    private final AuditQueryPort auditQueryPort;

    public AuditQueryService(AuditQueryPort auditQueryPort) {
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
    }

    @Override
    public List<AuditRecordSummary> execute(AuditRecordFilter filter) {
        Objects.requireNonNull(filter, "Filter must not be null");
        return auditQueryPort.findByFilter(filter);
    }
}
