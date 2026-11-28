package com.securedoc.platform.api.dto;

import com.documentplatform.fields.FieldSpec;

import java.math.BigDecimal;
import java.util.List;

/** Task 0061: a case field's type and rules, as the screens use them. {@code validationType} is platform-fields' type. */
public record CaseFieldRules(
        String validationType,
        Integer exactLength,
        Integer minLength,
        Integer maxLength,
        String caseNormalization,
        boolean stripSeparators,
        List<String> allowedValues,
        String customPattern,
        String searchMode,
        boolean sensitive,
        boolean multiValue,
        String currency,
        String minValue,
        String maxValue,
        /* Task 0113 (U10): display names of the allowed values, by code, in the admin's order; only named ones. */
        java.util.Map<String, String> allowedValueNames
) {
    static CaseFieldRules of(FieldSpec spec) {
        return new CaseFieldRules(spec.type().name(), spec.exactLength(), spec.minLength(), spec.maxLength(),
                spec.normalisation().letterCase().name(),
                spec.normalisation().strip() == com.documentplatform.fields.Normalisation.Strip.SEPARATORS,
                spec.options() == null ? List.of() : spec.options(), spec.pattern(), spec.searchMode().name(), spec.sensitive(),
                spec.multiValue(), spec.currency(), text(spec.minValue()), text(spec.maxValue()), names(spec));
    }

    private static java.util.Map<String, String> names(FieldSpec spec) {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        if (spec.namedOptions() != null) {
            spec.namedOptions().forEach(o -> { if (o.name() != null) names.put(o.code(), o.name()); });
        }
        return names;
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
