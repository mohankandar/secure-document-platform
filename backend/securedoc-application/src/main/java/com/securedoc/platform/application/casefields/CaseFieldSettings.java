package com.securedoc.platform.application.casefields;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValuesCheck;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0061: an entity's case field definitions, kept by platform-core's {@code platform-fields}, with LexaVault's own
 * rules on top: exactly one active Primary field, which is required and shown; a case field or a redaction item, never
 * both; locked items are never a field; removing a field switches it off. Called in one transaction, so a list is saved
 * as a whole or not at all.
 */
public final class CaseFieldSettings {
    /** The app's own setting of a field, attached by its key. */
    public static final String EXTRACTABLE = "extractable";
    static final int MAX_LABEL = 128;
    static final int MAX_KEY = 64;

    private final FieldDefinitions definitions;
    private final FieldValuesCheck values;
    private final CaseFieldLock lock;
    private final boolean sensitiveAvailable;

    public CaseFieldSettings(FieldDefinitions definitions, FieldValuesCheck values, CaseFieldLock lock,
                             boolean sensitiveAvailable) {
        this.definitions = Objects.requireNonNull(definitions);
        this.values = Objects.requireNonNull(values);
        this.lock = Objects.requireNonNull(lock);
        this.sensitiveAvailable = sensitiveAvailable;
    }

    /**
     * One field of the list to save.
     *
     * @param id         null for a new field; else the field's id (a switched-off field is switched on again)
     * @param type       null keeps the stored type and rules (a caller that only changes names and flags)
     * @param searchMode null: not searchable, or EXACT when {@code searchable}
     */
    public record Input(UUID id, String label, boolean required, boolean primary, boolean searchable, boolean extractable,
                        boolean displayable, String type, Integer exactLength, Integer minLength, Integer maxLength,
                        String letterCase, Boolean stripSeparators, Set<String> options, String pattern, String searchMode,
                        Boolean sensitive, Boolean multiValue, String currency, String minValue, String maxValue) {
        public Input(UUID id, String label, boolean required, boolean primary, boolean searchable, boolean extractable,
                     boolean displayable, String type, Integer exactLength, Integer minLength, Integer maxLength,
                     String letterCase, Boolean stripSeparators, Set<String> options, String pattern) {
            this(id, label, required, primary, searchable, extractable, displayable, type, exactLength, minLength, maxLength,
                    letterCase, stripSeparators, options, pattern, null, null, null, null, null, null);
        }

        boolean hasRules() {
            return type != null && !type.isBlank();
        }
    }

    /** A field with LexaVault's own setting. {@code locked}: values exist, so type, sensitive and Primary stay. */
    public record Field(FieldDefinition definition, boolean extractable, boolean locked) {
        public UUID id() {
            return definition.id();
        }

        public String key() {
            return definition.key().value();
        }

        public String label() {
            return definition.spec().label();
        }

        public FieldSpec spec() {
            return definition.spec();
        }
    }

    public List<FieldDefinition> active(TenantId tenantId) {
        return definitions.active(CaseFieldService.scope(tenantId)).stream().sorted(CaseFieldService.ORDER).toList();
    }

    /** Active and switched-off fields, in display order. */
    public List<Field> list(TenantId tenantId) {
        FieldScope scope = CaseFieldService.scope(tenantId);
        return definitions.list(scope).stream().sorted(CaseFieldService.ORDER).map(d -> field(scope, d)).toList();
    }

    public boolean extractable(TenantId tenantId, FieldDefinition definition) {
        return extractable(CaseFieldService.scope(tenantId), definition);
    }

    public boolean sensitiveAvailable() {
        return sensitiveAvailable;
    }

    private boolean extractable(FieldScope scope, FieldDefinition definition) {
        return "true".equals(definitions.extensions(scope, definition.key()).get(EXTRACTABLE));
    }

    private Field field(FieldScope scope, FieldDefinition definition) {
        return new Field(definition, extractable(scope, definition), values.valuesExist(scope, definition.key()));
    }

    /** Saves the list as the entity's active fields, in its order; a field that is not in it is switched off. */
    public List<FieldDefinition> save(TenantId tenantId, ActorId actorId, List<Input> requested,
                                      java.util.function.Supplier<? extends Collection<EntityType>> policy) {
        lock.changing(tenantId);
        checkList(requested);
        // Read under the lock, so the policy is not changed meanwhile.
        Collection<EntityType> redacted = policy.get();
        FieldScope scope = CaseFieldService.scope(tenantId);
        Map<UUID, FieldDefinition> stored = new LinkedHashMap<>();
        definitions.list(scope).forEach(d -> stored.put(d.id(), d));
        Set<String> usedKeys = new HashSet<>();
        stored.values().forEach(d -> usedKeys.add(d.key().folded()));

        Map<UUID, FieldSpec> changed = new LinkedHashMap<>();
        List<FieldSpec> created = new ArrayList<>();
        Map<String, Boolean> extractable = new LinkedHashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            Input input = requested.get(i);
            FieldDefinition existing = null;
            if (input.id() != null) {
                existing = stored.get(input.id());
                if (existing == null) {
                    throw new ValidationException(LvErrorCode.CFG_001, "Case field does not belong to this entity");
                }
            }
            String key = existing == null ? uniqueKey(input.label(), usedKeys) : existing.key().value();
            usedKeys.add(key.toLowerCase(Locale.ROOT));
            requireNotRedacted(input.label(), key, redacted);
            FieldSpec spec = spec(scope, input, existing, key, i + 1);
            if (spec.sensitive() && !sensitiveAvailable && (existing == null || !existing.spec().sensitive())) {
                throw new ValidationException(LvErrorCode.CFG_012,
                        "A sensitive case field needs the key service, and none is set up: " + input.label().trim());
            }
            if (existing == null) created.add(spec);
            else changed.put(existing.id(), spec);
            extractable.put(key, input.extractable());
        }
        String by = actorId.value().toString();
        // First what gives up the Primary place, then the rest, then the new ones.
        for (FieldDefinition definition : stored.values()) {
            if (definition.active() && !changed.containsKey(definition.id())) {
                guarded(definition.spec().label(), () -> definitions.switchOff(scope, definition.key(), by));
            }
        }
        for (boolean primary : new boolean[] {false, true}) {
            changed.forEach((id, spec) -> {
                if (spec.primary() == primary) change(scope, stored.get(id), spec, by);
            });
        }
        created.forEach(spec -> guarded(spec.label(), () -> definitions.create(scope, spec, by)));
        extractable.forEach((key, on) -> definitions.find(scope, com.documentplatform.fields.FieldKey.of(key)).ifPresent(d -> {
            if (extractable(scope, d) != on) definitions.putExtension(scope, d.key(), EXTRACTABLE, Boolean.toString(on));
        }));
        return active(tenantId);
    }

    private void change(FieldScope scope, FieldDefinition stored, FieldSpec spec, String by) {
        guarded(spec.label(), () -> {
            FieldDefinition current = stored.active() ? stored : definitions.switchOn(scope, stored.key(), by);
            return definitions.change(scope, spec, current.version(), by);
        });
    }

    /** The names of the list, one Primary, and what a Primary field must be. */
    private static void checkList(List<Input> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "At least one active case field is required");
        }
        if (requested.stream().filter(Input::primary).count() != 1) {
            throw new ValidationException(LvErrorCode.CFG_001, "Exactly one active case field must be Primary");
        }
        Input primary = requested.stream().filter(Input::primary).findFirst().orElseThrow();
        if (!primary.required()) {
            throw new ValidationException(LvErrorCode.CFG_001, "The Primary case field must also be Required");
        }
        if (!primary.displayable()) {
            throw new ValidationException(LvErrorCode.CFG_001, "The Primary case field must be Displayable");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (Input input : requested) {
            if (input.label() == null || input.label().trim().isEmpty()) {
                throw new ValidationException(LvErrorCode.CFG_001, "Case field label is required");
            }
            if (input.label().trim().length() > MAX_LABEL) {
                throw new ValidationException(LvErrorCode.CFG_001, "Case field label must be 128 characters or fewer");
            }
            if (input.id() != null && !ids.add(input.id())) {
                throw new ValidationException(LvErrorCode.CFG_001, "Duplicate case field id");
            }
            if (!labels.add(input.label().trim().toLowerCase(Locale.ROOT))) {
                throw new ValidationException(LvErrorCode.CFG_001, "Case field labels must be unique");
            }
        }
    }

    private static void requireNotRedacted(String label, String key, Collection<EntityType> redacted) {
        List<EntityType> conflicts = CaseFieldRedactionRule.conflicts(label, key, redacted);
        if (conflicts.isEmpty()) return;
        EntityType first = conflicts.stream().filter(CaseFieldRedactionRule::never).findFirst().orElse(conflicts.getFirst());
        String item = CaseFieldRedactionRule.nameOf(first);
        if (CaseFieldRedactionRule.never(first)) {
            throw new ValidationException(LvErrorCode.CFG_013, label.trim() + " can never be a case field: " + item
                    + " is a locked item");
        }
        throw new ValidationException(LvErrorCode.CFG_014, label.trim() + " can't be a case field while " + item
                + " is redacted in this entity");
    }

    /** The other direction: an item that an active case field is can't be switched on for redaction. */
    public void requireNoField(TenantId tenantId, Collection<EntityType> redacted, Collection<EntityType> before) {
        lock.changing(tenantId);
        for (FieldDefinition definition : definitions.active(CaseFieldService.scope(tenantId))) {
            for (EntityType kind : CaseFieldRedactionRule.itemsOf(definition.spec().label(), definition.key().value())) {
                if (redacted.contains(kind) && !before.contains(kind)) {
                    throw new ValidationException(LvErrorCode.CFG_014, CaseFieldRedactionRule.nameOf(kind)
                            + " can't be redacted while the case field " + definition.spec().label() + " is active");
                }
            }
        }
    }

    private FieldSpec spec(FieldScope scope, Input input, FieldDefinition existing, String key, int order) {
        return guarded(input.label().trim(), () -> {
            FieldSpec.Builder b;
            if (!input.hasRules()) {
                b = existing != null ? existing.spec().toBuilder()
                        : FieldSpec.builder(key, input.label().trim(), FieldType.TEXT)
                                .normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE);
                b.sensitive(flag(input.sensitive(), existing != null && existing.spec().sensitive()));
                b.multiValue(flag(input.multiValue(), existing != null && existing.spec().multiValue()));
            } else {
                b = withRules(input, key, existing);
            }
            boolean sensitive = existing == null ? flag(input.sensitive(), FieldSpec.suggestsSensitive(key,
                    input.label().trim(), type(input, existing)) && sensitiveAvailable)
                    : flag(input.sensitive(), existing.spec().sensitive());
            SearchMode mode = !input.searchable() || sensitive ? SearchMode.EXACT : mode(input.searchMode());
            FieldSpec spec = b.label(input.label().trim()).required(input.required()).primary(input.primary())
                    .sensitive(sensitive).displayable(input.displayable()).displayOrder(order).active(true)
                    .searchable(input.searchable()).build();
            spec = input.searchable() ? spec.toBuilder().searchable(mode).build() : spec;
            if (existing != null) requireSameForm(scope, existing, spec);
            return spec;
        });
    }

    private static FieldSpec.Builder withRules(Input input, String key, FieldDefinition existing) {
        FieldType type = type(input, existing);
        FieldSpec.Builder b = FieldSpec.builder(key, input.label().trim(), type)
                .exactLength(input.exactLength()).minLength(input.minLength()).maxLength(input.maxLength())
                .multiValue(flag(input.multiValue(), existing != null && existing.spec().multiValue()));
        if (input.currency() != null && !input.currency().isBlank()) b.currency(input.currency().trim().toUpperCase(Locale.ROOT));
        if (input.pattern() != null && !input.pattern().isBlank()) b.pattern(input.pattern().trim());
        Set<String> options = new LinkedHashSet<>();
        if (input.options() != null) input.options().forEach(o -> { if (o != null && !o.isBlank()) options.add(o.trim()); });
        if (!options.isEmpty()) b.options(List.copyOf(options));
        if (type.takesNormalisation()) {
            b.normalisation(letterCase(input.letterCase()), Boolean.TRUE.equals(input.stripSeparators())
                    ? Normalisation.Strip.SEPARATORS : Normalisation.Strip.NONE);
        }
        return b.minValue(number(input.minValue())).maxValue(number(input.maxValue()));
    }

    private static FieldType type(Input input, FieldDefinition existing) {
        if (!input.hasRules()) return existing == null ? FieldType.TEXT : existing.type();
        try {
            return FieldType.valueOf(input.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported case field type");
        }
    }

    /** What would make the values already kept compare differently stays as it is. */
    private void requireSameForm(FieldScope scope, FieldDefinition existing, FieldSpec spec) {
        FieldSpec now = existing.spec();
        if (existing.type() == spec.type() && !now.normalisation().equals(spec.normalisation())) {
            throw new ValidationException(LvErrorCode.CFG_001,
                    "Canonical normalization cannot be changed for an existing case field");
        }
        if (now.multiValue() != spec.multiValue() && values.valuesExist(scope, existing.key())) {
            throw new ValidationException(LvErrorCode.CFG_015, spec.label()
                    + " has values: whether it takes one or several can't change. Add a new field instead");
        }
    }

    private interface Step<T> {
        T run();
    }

    /** The platform's refusals as LexaVault's. Its messages name the rule and the setting, never a value. */
    private static <T> T guarded(String label, Step<T> step) {
        try {
            return step.run();
        } catch (FieldDefinitionException e) {
            throw switch (e.code()) {
                case FIELD_TYPE_LOCKED, FIELD_SENSITIVE_LOCKED, FIELD_PRIMARY_LOCKED, FIELD_KEY_LOCKED ->
                        new ValidationException(LvErrorCode.CFG_015, label + " has values: its type, Sensitive and Primary "
                                + "can't change. Add a new field instead");
                case FIELD_CHANGED_MEANWHILE -> new ValidationException(LvErrorCode.CFG_001,
                        label + " was changed by someone else meanwhile. Reload the page and try again");
                case FIELD_PATTERN_INVALID -> invalid(label, "the pattern is not a valid regular expression");
                case FIELD_OPTION_REPEATED -> invalid(label, "two values to choose from are the same");
                default -> invalid(label, e.getMessage());
            };
        }
    }

    private static ValidationException invalid(String label, String why) {
        return new ValidationException(LvErrorCode.CFG_001, "Invalid validation configuration for " + label + ": " + why);
    }

    private static boolean flag(Boolean given, boolean otherwise) {
        return given != null ? given : otherwise;
    }

    private static SearchMode mode(String value) {
        if (value == null || value.isBlank()) return SearchMode.EXACT;
        try {
            return SearchMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported case field search");
        }
    }

    private static Normalisation.LetterCase letterCase(String value) {
        if (value == null || value.isBlank()) return Normalisation.LetterCase.UPPER;
        try {
            return Normalisation.LetterCase.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Unsupported case field capitals setting");
        }
    }

    private static BigDecimal number(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "The lowest and highest value must be numbers");
        }
    }

    private static String uniqueKey(String label, Set<String> usedKeys) {
        String base = label.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isBlank() || !Character.isLetter(base.charAt(0))) base = "identifier_" + base;
        if (base.length() > 56) base = base.substring(0, 56);
        String candidate = base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) {
            String ending = "_" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), MAX_KEY - ending.length())) + ending;
        }
        return candidate;
    }
}
