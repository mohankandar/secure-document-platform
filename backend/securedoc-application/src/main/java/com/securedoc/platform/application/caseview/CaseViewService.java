package com.securedoc.platform.application.caseview;

import com.documentplatform.fields.FieldDefinition;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CaseViewStore;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Task 0108: reads and saves a person's View menu choices. A save that names anything the entity does not offer (a
 * column that is not a displayable case field or a built-in one, a sort on a field that is not the primary one) is
 * refused whole (fail closed). A read never fails on settings that were valid once: what the admin has since switched
 * off is left out, and an unknown sort falls back to Received.
 */
public final class CaseViewService {
    public static final int MAX_COLUMNS = 40;

    private final CaseViewStore store;
    private final Function<TenantId, List<FieldDefinition>> activeFields;
    private final Function<TenantId, Waiting> waiting;

    /** @param activeFields the entity's active case fields in the admin's display order */
    public CaseViewService(CaseViewStore store, Function<TenantId, List<FieldDefinition>> activeFields) {
        this(store, activeFields, tenant -> Waiting.DEFAULT);
    }

    /** @param waiting the entity's amber and red waiting times (Cases page settings, task 0108) */
    public CaseViewService(CaseViewStore store, Function<TenantId, List<FieldDefinition>> activeFields,
                           Function<TenantId, Waiting> waiting) {
        this.store = Objects.requireNonNull(store);
        this.activeFields = Objects.requireNonNull(activeFields);
        this.waiting = Objects.requireNonNull(waiting);
    }

    /** When a Needs-you row's waiting time turns amber and red, in minutes (the entity's Business Admin setting). */
    public record Waiting(int amberMinutes, int redMinutes) {
        public static final Waiting DEFAULT = new Waiting(240, 480);
    }

    /** What the View menu may offer this entity. */
    public record Options(List<String> sorts, List<String> columns) { }

    public record View(CaseViewSettings settings, Options options, Waiting waiting) { }

    public View read(TenantId tenantId, ActorId actorId) {
        Options options = options(tenantId);
        CaseViewSettings stored = store.find(tenantId, actorId).orElse(CaseViewSettings.DEFAULT);
        String sort = options.sorts().contains(stored.sort()) ? stored.sort() : CaseViewSettings.RECEIVED;
        String order = "desc".equals(stored.order()) ? "desc" : "asc";
        String density = "compact".equals(stored.density()) ? "compact" : "comfortable";
        List<String> columns = stored.columns() == null ? null
                : stored.columns().stream().filter(options.columns()::contains).toList();
        return new View(new CaseViewSettings(sort, order, columns, density), options, waiting.apply(tenantId));
    }

    public View save(TenantId tenantId, ActorId actorId, CaseViewSettings requested) {
        if (requested == null) throw invalid("The view settings are missing");
        Options options = options(tenantId);
        if (requested.sort() == null || !options.sorts().contains(requested.sort())) {
            throw invalid("Sort by must be Received, Waiting longest, Priority or the primary case field");
        }
        if (!"asc".equals(requested.order()) && !"desc".equals(requested.order())) {
            throw invalid("The order must be asc or desc");
        }
        if (!"compact".equals(requested.density()) && !"comfortable".equals(requested.density())) {
            throw invalid("Density must be compact or comfortable");
        }
        List<String> columns = null;
        if (requested.columns() != null) {
            if (requested.columns().size() > MAX_COLUMNS) throw invalid("Too many columns");
            Set<String> seen = new HashSet<>();
            for (String column : requested.columns()) {
                if (column == null || !options.columns().contains(column)) {
                    throw invalid("A column is not one this entity offers");
                }
                if (!seen.add(column)) throw invalid("A column is listed twice");
            }
            // Kept in the order the View menu offers them (the admin's order, then the built-in ones).
            columns = options.columns().stream().filter(seen::contains).toList();
        }
        store.save(tenantId, actorId, new CaseViewSettings(requested.sort(), requested.order(), columns,
                requested.density()));
        return read(tenantId, actorId);
    }

    Options options(TenantId tenantId) {
        List<FieldDefinition> fields = activeFields.apply(tenantId);
        List<String> sorts = new ArrayList<>(List.of(CaseViewSettings.RECEIVED, CaseViewSettings.WAITING_LONGEST,
                CaseViewSettings.PRIORITY));
        Set<String> columns = new LinkedHashSet<>();
        for (FieldDefinition field : fields) {
            if (field.spec().primary()) sorts.add(CaseViewSettings.FIELD_PREFIX + field.key().value());
            if (field.spec().displayable()) columns.add(field.key().value());
        }
        columns.addAll(CaseViewSettings.BUILT_IN_COLUMNS);
        return new Options(List.copyOf(sorts), List.copyOf(columns));
    }

    private static ValidationException invalid(String message) {
        return new ValidationException(LvErrorCode.CFG_001, message);
    }
}
