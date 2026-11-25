package com.securedoc.platform.application.casefields;

import com.documentplatform.fields.FieldValue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Task 0061: one field's values as one text, the form they travel in from the entry points (API, trigger file, polling,
 * scan) to {@link CaseFieldService}: several values are separated by {@link #VALUE_SEPARATOR}, the parts of an address
 * (street, second street line, city, state, ZIP, country) by {@link #PART_SEPARATOR}. Neither can be part of a value:
 * control characters are refused.
 */
public final class CaseFieldText {
    public static final String VALUE_SEPARATOR = String.valueOf((char) 0x1e);
    public static final String PART_SEPARATOR = String.valueOf((char) 0x1f);

    private CaseFieldText() {
    }

    public static String address(String street, String street2, String city, String state, String zip, String country) {
        return String.join(PART_SEPARATOR, text(street), text(street2), text(city), text(state), text(zip), text(country));
    }

    public static String several(List<String> values) {
        return String.join(VALUE_SEPARATOR, values);
    }

    static List<FieldValue> read(String text) {
        List<FieldValue> values = new ArrayList<>();
        for (String one : text.split(VALUE_SEPARATOR, -1)) {
            if (one.isBlank()) continue;
            String[] parts = one.split(PART_SEPARATOR, -1);
            // Anything but six parts stays text, so the field's own rules refuse it.
            values.add(parts.length == 6
                    ? new FieldValue.Address(parts[0].trim(), blankToNull(parts[1]), parts[2].trim(), parts[3].trim(),
                            parts[4].trim(), blankToNull(parts[5]) == null ? FieldValue.Address.US : parts[5].trim())
                    : FieldValue.text(one.trim()));
        }
        return values;
    }

    /** For people: one stored value, an address from its parts. */
    public static String shown(String stored) {
        return stored == null ? null : shown(read(stored));
    }

    /** For people: an address on one line, several values separated by "; ". */
    static String shown(List<FieldValue> values) {
        return values.stream().map(CaseFieldText::shown).collect(Collectors.joining("; "));
    }

    static String shown(FieldValue value) {
        if (value instanceof FieldValue.Text text) return text.value();
        FieldValue.Address a = (FieldValue.Address) value;
        return a.street() + (blankToNull(a.street2()) == null ? "" : ", " + a.street2()) + ", " + a.city() + ", "
                + a.state() + " " + a.zip();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
