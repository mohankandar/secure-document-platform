package com.securedoc.platform.application.intake.query;

/** One case field of a case. A sensitive value is hidden unless the role may see it (task 0061); no value is in its text form. */
public record BusinessIdentifierSummary(
        String key,
        String label,
        String value,
        boolean primary,
        boolean searchable,
        boolean displayable,
        int displayOrder,
        boolean sensitive
) {
    public BusinessIdentifierSummary(String key, String label, String value, boolean primary, boolean searchable,
                                     boolean displayable, int displayOrder) {
        this(key, label, value, primary, searchable, displayable, displayOrder, false);
    }

    public static BusinessIdentifierSummary of(com.securedoc.platform.application.casefields.CaseFieldSnapshot.Value v) {
        return new BusinessIdentifierSummary(v.key(), v.label(), v.value(), v.primary(), v.searchable(), v.displayable(),
                v.displayOrder(), v.sensitive());
    }

    @Override
    public String toString() {
        return "BusinessIdentifierSummary[" + key + "]";
    }
}
