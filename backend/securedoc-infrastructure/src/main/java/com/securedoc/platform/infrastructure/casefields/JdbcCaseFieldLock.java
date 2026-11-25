package com.securedoc.platform.infrastructure.casefields;

import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Task 0061: a PostgreSQL advisory lock per entity, held until the transaction ends. */
@Component
public class JdbcCaseFieldLock implements CaseFieldLock {
    private static final String KEY = "hashtextextended('lexavault-case-fields:' || ?, 0)";
    private final JdbcTemplate jdbc;

    public JdbcCaseFieldLock(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void using(TenantId tenantId) {
        lock("pg_advisory_xact_lock_shared", tenantId);
    }

    @Override
    public void changing(TenantId tenantId) {
        lock("pg_advisory_xact_lock", tenantId);
    }

    private void lock(String function, TenantId tenantId) {
        jdbc.query("select " + function + "(" + KEY + ")", rs -> null, tenantId.value().toString());
    }
}
