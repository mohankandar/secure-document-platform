package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.port.out.IntakeRefusalPort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Table intake_refusals (migration 072). */
@Repository
public class JdbcIntakeRefusals implements IntakeRefusalPort {
    private static final Logger log = LoggerFactory.getLogger(JdbcIntakeRefusals.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate own;

    public JdbcIntakeRefusals(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(TenantId tenantId, String sourceKey, LvErrorCode code) {
        try {
            own.executeWithoutResult(status -> jdbc.update("""
                    insert into intake_refusals (id, tenant_id, source_key, lv_code, kind, occurred_at)
                    values (:id, :tenant, :source, :code, :kind, clock_timestamp())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenant", tenantId.value())
                    .addValue("source", sourceKey).addValue("code", code.code())
                    .addValue("kind", code == LvErrorCode.INT_017 ? "DUPLICATE" : "REFUSED")));
        } catch (RuntimeException failure) {
            log.warn("INTAKE_REFUSAL_NOT_COUNTED code={} exceptionType={}", code.code(), failure.getClass().getSimpleName());
        }
    }
}
