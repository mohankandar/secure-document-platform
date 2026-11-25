package com.securedoc.install;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.casefields.NoKeyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 0061: once, on a database from before it: the entity's case fields and the values of the business records cases
 * point to, from LexaVault's own tables to platform-fields, so that no case loses them when those tables are dropped
 * (066-case-fields.xml). Written to the mapping of the agreed design; a record keeps its id. Nothing becomes sensitive
 * here: the values were kept readable, and the Business Admin decides about new fields. Running it again changes nothing.
 * Output holds counts only.
 */
final class CaseFieldCarryOver {
    private static final String ACTOR = "schema-step";

    private final InstallDb db;
    private final PrintStream out;

    CaseFieldCarryOver(InstallDb db, PrintStream out) {
        this.db = db;
        this.out = out;
    }

    private record OldField(UUID tenant, UUID id, String key, boolean active, boolean primary, boolean required,
                            boolean extractable, FieldSpec spec) {
    }

    void run() {
        DriverManagerDataSource ds = new DriverManagerDataSource(db.url(), db.user(), db.password());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        try (var c = db.open()) {
            if (!SchemaStep.tableExists(c, "business_identifier_definitions")) return;
        } catch (SQLException e) {
            throw new InstallException(1, "SCHEMA_STEP_FAILED", "case fields: SQL state " + e.getSQLState());
        }
        try {
            carryOver(ds, jdbc);
        } catch (InstallException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never the driver's or the platform's text: it can quote a value.
            throw new InstallException(1, "CASE_FIELDS_NOT_MOVED", "the case fields could not be moved, nothing was dropped: "
                    + e.getClass().getSimpleName());
        }
    }

    private void carryOver(DriverManagerDataSource ds, JdbcTemplate jdbc) {
        List<OldField> fields = jdbc.query("""
                select tenant_id, id, identifier_key, label, required_flag, primary_flag, searchable_flag, extractable_flag,
                       displayable_flag, display_order, active_flag, validation_type, exact_length, min_length, max_length,
                       case_normalization, strip_separators_flag, allowed_values, custom_pattern
                  from business_identifier_definitions order by tenant_id, display_order, identifier_key
                """, (rs, n) -> {
            boolean active = rs.getBoolean("active_flag");
            boolean primary = active && rs.getBoolean("primary_flag");
            return new OldField(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                    rs.getString("identifier_key"), active, primary, rs.getBoolean("required_flag"),
                    rs.getBoolean("extractable_flag"),
                    spec(rs.getString("identifier_key"), rs.getString("label"), rs.getString("validation_type"),
                            (Integer) rs.getObject("exact_length"), (Integer) rs.getObject("min_length"),
                            (Integer) rs.getObject("max_length"), rs.getString("case_normalization"),
                            rs.getBoolean("strip_separators_flag"), rs.getString("allowed_values"),
                            rs.getString("custom_pattern")).primary(primary).searchable(rs.getBoolean("searchable_flag"))
                            .displayable(rs.getBoolean("displayable_flag"))
                            .displayOrder(Math.min(rs.getInt("display_order"), FieldSpec.MAX_DISPLAY_ORDER)).build());
        });
        if (fields.isEmpty()) return;
        PostgresValueStore store = new PostgresValueStore(ds);
        PostgresFieldStore fieldStore = new PostgresFieldStore(ds);
        FieldDefinitions definitions = new FieldDefinitions(fieldStore, store);
        // Every field first as it can take values: active and not required. The final state follows the values.
        for (OldField field : fields) {
            if (definitions.find(scope(field.tenant()), FieldKey.of(field.key())).isEmpty()) {
                definitions.create(scope(field.tenant()), field.spec(), ACTOR);
            }
            definitions.putExtension(scope(field.tenant()), FieldKey.of(field.key()), CaseFieldSettings.EXTRACTABLE,
                    Boolean.toString(field.extractable()));
        }
        int[] counts = values(jdbc, () -> new FieldValues(customer(jdbc), fieldStore, store, new NoKeyService(),
                Clock.systemUTC()), fields);
        for (OldField field : fields) {
            FieldScope scope = scope(field.tenant());
            FieldDefinition now = definitions.find(scope, FieldKey.of(field.key())).orElseThrow();
            jdbc.update("update reporting_dimension_definitions set definition_id = ? where tenant_id = ? and definition_id = ?",
                    now.id(), field.tenant(), field.id());
            if (!now.active()) continue;
            if (!field.active()) {
                definitions.switchOff(scope, now.key(), ACTOR);
            } else if (now.spec().required() != (field.required() || field.primary())) {
                definitions.change(scope, now.spec().toBuilder().required(field.required() || field.primary()).build(),
                        now.version(), ACTOR);
            }
        }
        // 066 drops the old tables only with this.
        jdbc.execute("comment on table business_identifier_definitions is 'moved to fld_ (066)'");
        out.println("  case fields      moved " + fields.size() + " field(s), the values of " + counts[0]
                + " business record(s)" + (counts[1] == 0 ? "" : "; " + counts[1] + " value(s) no longer allowed were left out"));
    }

    /** Only records a case points to. Returns the records moved and the values left out. */
    private int[] values(JdbcTemplate jdbc, Supplier<FieldValues> fieldValues, List<OldField> fields) {
        Map<UUID, OldField> byId = new LinkedHashMap<>();
        fields.forEach(f -> byId.put(f.id(), f));
        Map<UUID, UUID> tenants = new LinkedHashMap<>();
        Map<UUID, Map<String, List<FieldValue>>> records = new LinkedHashMap<>();
        int[] counts = {0, 0};
        jdbc.query("""
                select bri.business_record_id, bri.tenant_id, bri.definition_id, bri.identifier_value
                  from business_record_identifiers bri
                  join business_records br on br.id = bri.business_record_id
                 where exists (select 1 from document_packages p where p.business_record_id = bri.business_record_id)
                 order by br.created_at, bri.business_record_id
                """, rs -> {
            UUID record = rs.getObject(1, UUID.class);
            OldField field = byId.get(rs.getObject(3, UUID.class));
            FieldValue value = FieldValue.text(rs.getString(4));
            tenants.put(record, rs.getObject(2, UUID.class));
            Map<String, List<FieldValue>> given = records.computeIfAbsent(record, r -> new LinkedHashMap<>());
            if (field == null || !FieldValidator.check(field.spec(), value).ok()) counts[1]++;
            else given.put(field.key(), List.of(value));
        });
        if (records.isEmpty()) return counts;
        FieldValues values = fieldValues.get();
        records.forEach((record, given) -> {
            if (given.isEmpty()) return;
            UUID tenant = tenants.get(record);
            RecordRef ref = RecordRef.of(scope(tenant), CaseFieldService.RECORD_TYPE, record.toString());
            String id;
            try {
                id = values.claim(ref, given).record().recordId();
            } catch (FieldValuesException conflict) {
                // Another record with the same main ID but for its capitals: this one keeps its other values, by its own id.
                Map<String, List<FieldValue>> rest = new LinkedHashMap<>(given);
                fields.stream().filter(f -> f.tenant().equals(tenant) && f.primary()).forEach(f -> {
                    if (rest.remove(f.key()) != null) counts[1]++;
                });
                id = values.claim(ref, rest).record().recordId();
            }
            if (!id.equals(record.toString())) {
                jdbc.update("alter table document_packages drop constraint if exists fk_document_package_business_record");
                jdbc.update("update document_packages set business_record_id = ? where business_record_id = ?",
                        UUID.fromString(id), record);
            }
            counts[0]++;
        });
        return counts;
    }

    private static CustomerId customer(JdbcTemplate jdbc) {
        List<String> customers = jdbc.queryForObject("select to_regclass('idn_customer') is not null", Boolean.class)
                ? jdbc.queryForList("select tenant_id from idn_customer", String.class) : new ArrayList<>();
        if (customers.size() != 1) {
            throw new InstallException(2, "CASE_FIELDS_NO_CUSTOMER", "the case fields of this database belong to the customer, "
                    + "and " + (customers.isEmpty() ? "none is stored yet: run setup with the release installed before, then "
                    + "this step again" : "more than one is stored") + ". Nothing was dropped");
        }
        return CustomerId.fromTenantId(Objects.requireNonNull(customers.getFirst()));
    }

    private static FieldScope scope(UUID tenant) {
        return CaseFieldService.scope(TenantId.of(tenant));
    }

    /** The mapping of the agreed design (Case-Fields-Design.md). */
    static FieldSpec.Builder spec(String key, String label, String validationType, Integer exact, Integer min, Integer max,
                                  String letterCase, boolean stripSeparators, String allowedValues, String pattern) {
        List<String> options = allowedValues == null ? List.of()
                : allowedValues.lines().map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
        String type = validationType == null ? "TEXT" : validationType;
        FieldType mapped = !options.isEmpty() ? FieldType.DROPDOWN : switch (type) {
            case "NUMERIC" -> FieldType.NUMBER;
            case "EMAIL" -> FieldType.EMAIL;
            case "UUID" -> FieldType.UUID;
            case "CUSTOM_PATTERN" -> FieldType.CUSTOM_PATTERN;
            default -> FieldType.TEXT;
        };
        FieldSpec.Builder b = FieldSpec.builder(key, label, mapped).sensitive(false);
        if (mapped == FieldType.DROPDOWN) b.options(options);
        if (mapped.takesPattern()) {
            b.pattern(switch (type) {
                case "NUMERIC" -> "[0-9]+";
                case "ALPHANUMERIC" -> "[A-Za-z0-9]+";
                case "CUSTOM_PATTERN" -> pattern;
                default -> null;
            });
        }
        if (mapped.takesLength()) b.exactLength(exact).minLength(min).maxLength(max);
        if (mapped.takesNormalisation()) {
            b.normalisation(Normalisation.LetterCase.valueOf(letterCase == null ? "UPPER" : letterCase),
                    stripSeparators ? Normalisation.Strip.SEPARATORS : Normalisation.Strip.NONE);
        }
        return b;
    }
}
