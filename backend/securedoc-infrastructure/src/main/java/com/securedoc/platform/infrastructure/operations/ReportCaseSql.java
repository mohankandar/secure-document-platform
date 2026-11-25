package com.securedoc.platform.infrastructure.operations;

import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.domain.document.SensitivityLevel;

/**
 * Task 0081: a case's source system and sensitivity level, for every report. {@code row} is the alias of a row with
 * {@code tenant_id} and {@code package_type}; {@code id} the case's id on it.
 */
final class ReportCaseSql {
    private ReportCaseSql() { }

    /** A connection's id, folder:installed, or type:(package type) for a case without a connection. */
    static String sourceSystem(String row, String id) {
        return """
                coalesce((select cp.source_key from case_priorities cp where cp.package_id=%2$s and cp.tenant_id=%1$s.tenant_id),
                         (select cast(ei.integration_connection_id as text) from external_intakes ei
                           where ei.package_id=%2$s and ei.tenant_id=%1$s.tenant_id),
                         case when %1$s.package_type='FOLDER_INTAKE' then 'folder:installed'
                              else concat('type:', %1$s.package_type) end)""".formatted(row, id);
    }

    static String rank(String column) {
        return "(case %s when 'HIGHLY_CONFIDENTIAL' then 2 when 'CONFIDENTIAL' then 1 else 0 end)".formatted(column);
    }

    /** The case's effective level: the highest of its own and its documents'. */
    static String caseLevelRank(String row, String id) {
        return """
                coalesce((select greatest(%3$s, coalesce((select max(%4$s) from documents ld
                           where ld.package_id=lp.id and ld.tenant_id=lp.tenant_id), 0))
                            from document_packages lp where lp.id=%2$s and lp.tenant_id=%1$s.tenant_id), 0)"""
                .formatted(row, id, rank("lp.sensitivity_level"), rank("ld.sensitivity_level"));
    }

    static int rank(SensitivityLevel level) {
        return level.ordinal();
    }

    /** Starts with " and" when there is anything to restrict. Parameters: sourceSystem, levelRank. */
    static String clause(ReportScope scope, String row, String id) {
        return (scope.everySource() ? "" : " and " + sourceSystem(row, id) + "=:sourceSystem")
                + (scope.everyLevel() ? "" : " and " + caseLevelRank(row, id) + "=:levelRank");
    }

    static int levelRank(ReportScope scope) {
        return rank(SensitivityLevel.valueOf(scope.level()));
    }
}
