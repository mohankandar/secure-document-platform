package com.securedoc.platform.application.casefields;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValueError;
import com.documentplatform.fields.delivery.FieldDelivery;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.RecordValues;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0061: the values of LexaVault's case fields, kept by platform-core's {@code platform-fields}: one business record
 * per primary value and entity, which many cases point to. Sensitive values are stored only sealed with the customer's
 * keys and are opened only when asked for. No value is in a log, an exception or a text form here.
 */
public final class CaseFieldService {
    public static final String PRODUCT = "LEXAVAULT";
    public static final String RECORD_TYPE = "business-record";
    private static final Logger log = LoggerFactory.getLogger(CaseFieldService.class);
    private static final int MAX_SENSITIVE_MATCHES = 500;

    private final FieldDefinitions definitions;
    private final FieldValues values;
    private final CaseFieldLock lock;
    private final Fingerprinter fingerprinter;
    private final CustomerId customer;

    /** @param fingerprinter null without a key service (local development only): sensitive values are then refused */
    public CaseFieldService(FieldDefinitions definitions, FieldValues values, CaseFieldLock lock,
                            Fingerprinter fingerprinter, CustomerId customer) {
        this.definitions = Objects.requireNonNull(definitions);
        this.values = Objects.requireNonNull(values);
        this.lock = Objects.requireNonNull(lock);
        this.fingerprinter = fingerprinter;
        this.customer = Objects.requireNonNull(customer);
    }

    public static FieldScope scope(TenantId tenantId) {
        return FieldScope.of(tenantId.value(), PRODUCT);
    }

    private static RecordRef ref(TenantId tenantId, BusinessRecordId id) {
        return RecordRef.of(scope(tenantId), RECORD_TYPE, id.value().toString());
    }

    /** The entity's active fields in display order. Refused while they are not set up for intake. */
    public List<FieldDefinition> definitions(TenantId tenantId) {
        List<FieldDefinition> active = active(tenantId);
        if (active.isEmpty()) {
            throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFIGURATION_MISSING,
                    "Case fields are not set up for this entity");
        }
        if (active.stream().filter(d -> d.spec().primary()).count() != 1) {
            throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFIGURATION_INVALID,
                    "Exactly one active case field must be Primary");
        }
        return active;
    }

    public List<FieldDefinition> active(TenantId tenantId) {
        return definitions.active(scope(tenantId)).stream().sorted(ORDER).toList();
    }

    static final Comparator<FieldDefinition> ORDER = Comparator
            .comparingInt((FieldDefinition d) -> d.spec().displayOrder()).thenComparing(d -> d.key().value());

    /**
     * The business record for these values: the one with the same primary value (missing fields are added, equal values
     * stay, a different value is refused), or a new one.
     */
    public CaseFieldSnapshot resolveOrCreate(TenantId tenantId, Map<String, String> supplied) {
        lock.using(tenantId);
        List<FieldDefinition> active = definitions(tenantId);
        Map<String, List<FieldValue>> given = given(active, supplied);
        for (FieldDefinition definition : active) {
            if ((definition.spec().required() || definition.spec().primary()) && !given.containsKey(definition.key().value())) {
                throw new IntakeValidationException(IntakeRejectionCode.MISSING_REQUIRED_IDENTIFIER,
                        "Missing required case field: " + definition.spec().label(),
                        "Case field " + definition.spec().label() + " is required");
            }
        }
        requireKeys(active, given);
        FieldValues.Claim claim;
        try {
            claim = values.claim(ref(tenantId, BusinessRecordId.generate()), given);
        } catch (CryptoException refused) {
            throw keyService(refused);
        } catch (FieldValuesException refused) {
            throw refused(refused, active);
        }
        BusinessRecordId id = BusinessRecordId.fromString(claim.record().recordId());
        return new CaseFieldSnapshot(id, claim.created(), List.of());
    }

    /** Whether sensitive values can be kept: only with a key service. */
    public boolean sensitiveAvailable() {
        return fingerprinter != null;
    }

    private void requireKeys(List<FieldDefinition> active, Map<String, List<FieldValue>> given) {
        if (fingerprinter != null) return;
        for (FieldDefinition definition : active) {
            if (definition.spec().sensitive() && given.containsKey(definition.key().value())) throw noKeyService();
        }
    }

    private static TechnicalDependencyException noKeyService() {
        return new TechnicalDependencyException(TechnicalDependency.KEY_SERVICE, TechnicalFailureCode.KEY_SERVICE_REFUSED,
                false, "A sensitive case field needs the key service, and none is set up", null);
    }

    /** The values by the fields' own keys. The names and values sent are not echoed. */
    private static Map<String, List<FieldValue>> given(List<FieldDefinition> active, Map<String, String> supplied) {
        Map<String, FieldDefinition> byKey = new LinkedHashMap<>();
        active.forEach(d -> byKey.put(d.key().folded(), d));
        Map<String, List<FieldValue>> given = new LinkedHashMap<>();
        if (supplied != null) {
            for (Map.Entry<String, String> entry : supplied.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                List<FieldValue> sent = CaseFieldText.read(entry.getValue());
                if (sent.isEmpty()) continue;
                FieldDefinition definition = byKey.get(entry.getKey().trim().toLowerCase(Locale.ROOT));
                if (definition == null) {
                    throw new IntakeValidationException(IntakeRejectionCode.UNKNOWN_IDENTIFIER, "Unknown case field",
                            "Every case field sent must be one of this entity's case fields");
                }
                if (given.put(definition.key().value(), sent) != null) {
                    throw new IntakeValidationException(IntakeRejectionCode.INVALID_IDENTIFIER_VALUE,
                            "Case field sent twice: " + definition.spec().label(),
                            "Case field " + definition.spec().label() + " was sent twice");
                }
            }
        }
        return given;
    }

    private static RuntimeException refused(FieldValuesException e, List<FieldDefinition> active) {
        if (e.code() == FieldValuesError.VALUES_INVALID && !e.problems().isEmpty()) {
            var problem = e.problems().getFirst();
            String label = label(active, problem.key() == null ? "" : problem.key().value());
            IntakeRejectionCode code = problem.code() == ValueError.VALUE_REQUIRED
                    ? IntakeRejectionCode.MISSING_REQUIRED_IDENTIFIER
                    : problem.code() == ValueError.FIELD_UNKNOWN || problem.code() == ValueError.FIELD_INACTIVE
                    ? IntakeRejectionCode.UNKNOWN_IDENTIFIER : IntakeRejectionCode.INVALID_IDENTIFIER_VALUE;
            // The platform's messages name the field and its rule, never a value.
            return new IntakeValidationException(code, problem.message(),
                    "Case field " + label + " breaks its rule (type, length, pattern or allowed values)");
        }
        if (e.code() == FieldValuesError.VALUES_CONFLICT) {
            // Task 0113: the fields that differ, by their keys (platform-core 1.2.0; the message was read before).
            List<String> labels = active.stream().filter(d -> e.conflictingKeys().contains(d.key()))
                    .map(d -> d.spec().label()).toList();
            String named = labels.isEmpty() ? "values" : String.join(", ", labels);
            return new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFLICT,
                    "Case field conflicts with the existing record: " + named,
                    "Case field " + named + " differs from the existing case with the same Primary field");
        }
        return e;
    }

    private static String label(List<FieldDefinition> active, String key) {
        return active.stream().filter(d -> d.key().value().equals(key)).map(d -> d.spec().label()).findFirst().orElse("sent");
    }

    /** A record's values for a screen, without any sensitive value. Empty when its values were erased. */
    public CaseFieldSnapshot get(BusinessRecordId id, TenantId tenantId) {
        return get(id, tenantId, active(tenantId), false);
    }

    /** As above, for a list: the entity's fields are read once by the caller. */
    public CaseFieldSnapshot get(BusinessRecordId id, TenantId tenantId, List<FieldDefinition> active) {
        return get(id, tenantId, active, false);
    }

    /** With the sensitive values, for the roles allowed to see them. Hidden when the key service does not answer. */
    public CaseFieldSnapshot getWithSensitive(BusinessRecordId id, TenantId tenantId) {
        return get(id, tenantId, active(tenantId), true);
    }

    private CaseFieldSnapshot get(BusinessRecordId id, TenantId tenantId, List<FieldDefinition> active, boolean sensitive) {
        RecordValues stored = read(ref(tenantId, id));
        if (stored == null) return new CaseFieldSnapshot(id, false, List.of());
        if (sensitive && stored.fields().values().stream().anyMatch(RecordValues.Field::hidden)) {
            try {
                stored = values.readSensitive(stored.ref());
            } catch (CryptoException closed) {
                log.warn("CASE_FIELDS_NOT_OPENED record={} code={}", id, closed.code().name());
            } catch (FieldValuesException gone) {
                if (gone.code() != FieldValuesError.VALUES_RECORD_UNKNOWN) throw gone;
            }
        }
        List<CaseFieldSnapshot.Value> shown = new ArrayList<>();
        for (FieldDefinition definition : active) {
            RecordValues.Field field = stored.field(definition.key().value()).orElse(null);
            if (field == null || field.count() == 0) continue;
            String text = field.hidden() ? CaseFieldSnapshot.HIDDEN
                    : CaseFieldText.shown(field.values().stream().map(ValidValue::display).toList());
            shown.add(new CaseFieldSnapshot.Value(definition.key().value(), definition.spec().label(), text,
                    definition.spec().primary(), definition.spec().searchable(), definition.spec().displayable(),
                    definition.spec().displayOrder(), field.sensitive()));
        }
        return new CaseFieldSnapshot(id, false, shown);
    }

    private RecordValues read(RecordRef ref) {
        try {
            return values.read(ref);
        } catch (FieldValuesException e) {
            if (e.code() == FieldValuesError.VALUES_RECORD_UNKNOWN) return null;
            throw e;
        }
    }

    /**
     * Every active field's value for a delivery, sensitive ones opened: the plain texts by key, and the same as
     * platform-core's delivery format (key, type and value, with the list of a multi-value field). Values that can't be
     * opened stop the delivery; it is never sent without them.
     */
    public Delivery forDelivery(BusinessRecordId id, TenantId tenantId) {
        List<FieldDefinition> active = active(tenantId);
        RecordValues stored = read(ref(tenantId, id));
        if (stored == null) return Delivery.NONE;
        if (stored.fields().values().stream().anyMatch(RecordValues.Field::hidden)) {
            if (fingerprinter == null) throw noKeyService();
            try {
                stored = values.readSensitive(stored.ref());
            } catch (CryptoException closed) {
                throw keyService(closed);
            }
        }
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, List<FieldValue>> sent = new LinkedHashMap<>();
        for (FieldDefinition definition : active) {
            List<ValidValue> held = stored.values(definition.key().value());
            if (held.isEmpty()) continue;
            List<FieldValue> display = held.stream().map(ValidValue::display).toList();
            texts.put(definition.key().value(), CaseFieldText.shown(display));
            sent.put(definition.key().value(), display);
        }
        // Checked without "required": a case from before a field became required is still delivered.
        FieldSet set = FieldSet.of(active.stream().map(d -> d.spec().toBuilder().required(false).build()).toList());
        var valid = set.validate(sent);
        return new Delivery(Map.copyOf(texts), valid.accepted() && !sent.isEmpty() ? FieldDelivery.write(set, valid) : null, set);
    }

    /** @param fieldList the field list the document was written with (null without values)
        @param document platform-core's field delivery document; null when there is nothing to send or a rule has changed since */
    public record Delivery(Map<String, String> texts, String document, FieldSet fieldList) {
        static final Delivery NONE = new Delivery(Map.of(), null, null);

        @Override
        public String toString() {
            return "Delivery[fields=" + texts.size() + "]";
        }
    }

    /**
     * The records whose searchable sensitive fields hold exactly this (keyed fingerprints; capitals and extra spaces
     * ignored). Nothing when the term can't be such a value, or the key service does not answer.
     */
    public List<UUID> findSensitive(TenantId tenantId, String term) {
        if (fingerprinter == null || term == null || term.isBlank()) return List.of();
        List<UUID> found = new ArrayList<>();
        for (FieldDefinition definition : active(tenantId)) {
            if (!definition.spec().sensitive() || !definition.spec().searchable()) continue;
            FieldValue value = FieldValue.text(term.trim());
            if (!FieldValidator.check(definition.spec(), value).ok()) continue;
            try {
                values.search(scope(tenantId), RECORD_TYPE, definition.key().value(), SearchMode.EXACT, value,
                        MAX_SENSITIVE_MATCHES).records().forEach(r -> found.add(UUID.fromString(r.recordId())));
            } catch (CryptoException closed) {
                log.warn("CASE_FIELD_SEARCH_NOT_DONE code={}", closed.code().name());
                return List.of();
            }
        }
        return found;
    }

    /**
     * The values as they may take part in a plain digest of a request (the checks for repeats): each value of a sensitive
     * field replaced by its keyed fingerprint, so the digest tells nothing about it. One map per usable version of the
     * fingerprint key, the latest first; a request from before a key rotation matches one of the others.
     */
    public List<Map<String, String>> forDigest(TenantId tenantId, Map<String, String> supplied) {
        Map<String, FieldDefinition> sensitive = new LinkedHashMap<>();
        definitions.list(scope(tenantId)).stream().filter(d -> d.spec().sensitive())
                .forEach(d -> sensitive.put(d.key().folded(), d));
        boolean any = supplied.entrySet().stream().anyMatch(e -> sensitive.containsKey(folded(e.getKey()))
                && !e.getValue().isBlank());
        if (!any) return List.of(supplied);
        if (fingerprinter == null) throw noKeyService();
        try {
            List<Map<String, String>> forms = new ArrayList<>();
            for (int version : fingerprinter.usableVersions(customer).stream().sorted(Comparator.reverseOrder()).toList()) {
                Map<String, String> form = new LinkedHashMap<>();
                supplied.forEach((key, value) -> {
                    FieldDefinition field = sensitive.get(folded(key));
                    form.put(key, field == null || value.isBlank() ? value : fingerprinter.of(customer, version,
                            "lexavault-request:" + tenantId.value() + ":" + field.key().folded(), value.trim()).text());
                });
                forms.add(form);
            }
            return forms;
        } catch (CryptoException refused) {
            throw keyService(refused);
        }
    }

    private static String folded(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    /** The key service's code decides: down, or refused. The exception holds ids and codes only. */
    static TechnicalDependencyException keyService(CryptoException e) {
        TechnicalFailureCode code = switch (e.code()) {
            case CRYPTO_KEY_SERVICE_UNREACHABLE, CRYPTO_KEY_SERVICE_TLS, CRYPTO_KEY_SERVICE_SEALED, CRYPTO_KEY_SERVICE_ERROR ->
                    TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE;
            default -> TechnicalFailureCode.KEY_SERVICE_REFUSED;
        };
        return new TechnicalDependencyException(TechnicalDependency.KEY_SERVICE, code, true,
                "Key service: " + e.code().name(), e);
    }
}
