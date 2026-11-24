package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PackageRejectionRepository;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.RejectionReason;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Table package_rejections (migration 071). */
@Component
public class JdbcPackageRejectionRepository implements PackageRejectionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPackageRejectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(TenantId tenantId, PackageId packageId, RejectionReason reason, ActorId rejectedBy, Instant at) {
        jdbc.update("""
                insert into package_rejections (package_id, tenant_id, reason_code, rejected_by, rejected_at)
                values (:package, :tenant, :code, :by, :at)
                """, new MapSqlParameterSource("package", packageId.value()).addValue("tenant", tenantId.value())
                .addValue("code", reason.code().code()).addValue("by", rejectedBy.value())
                .addValue("at", Timestamp.from(at)));
    }

    @Override
    public Optional<RejectionReason> find(TenantId tenantId, PackageId packageId) {
        return jdbc.queryForList("select reason_code from package_rejections where package_id = :package and tenant_id = :tenant",
                        new MapSqlParameterSource("package", packageId.value()).addValue("tenant", tenantId.value()), String.class)
                .stream().findFirst().flatMap(RejectionReason::fromCode);
    }
}
