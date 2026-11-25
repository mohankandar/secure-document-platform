package com.securedoc.platform.api.dto;

public record BusinessIdentifierResponse(
        String key,
        String label,
        String value,
        boolean primary,
        boolean searchable,
        boolean displayable,
        int displayOrder,
        /* Task 0061: kept encrypted; the value is hidden unless the role may see it. */
        boolean sensitive
) {
    public static BusinessIdentifierResponse of(com.securedoc.platform.application.intake.query.BusinessIdentifierSummary i) {
        return new BusinessIdentifierResponse(i.key(), i.label(), i.value(), i.primary(), i.searchable(), i.displayable(),
                i.displayOrder(), i.sensitive());
    }

    @Override
    public String toString() {
        return "BusinessIdentifierResponse[" + key + "]";
    }
}
