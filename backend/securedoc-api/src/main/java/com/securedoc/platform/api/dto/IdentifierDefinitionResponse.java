package com.securedoc.platform.api.dto;

import com.documentplatform.fields.FieldDefinition;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record IdentifierDefinitionResponse(
        String key,
        String label,
        boolean required,
        boolean primary,
        boolean searchable,
        boolean extractable,
        boolean displayable,
        int displayOrder,
        @JsonUnwrapped CaseFieldRules rules
) {
    public static IdentifierDefinitionResponse of(FieldDefinition d, boolean extractable) {
        var spec = d.spec();
        return new IdentifierDefinitionResponse(d.key().value(), spec.label(), spec.required(), spec.primary(),
                spec.searchable(), extractable, spec.displayable(), spec.displayOrder(), CaseFieldRules.of(spec));
    }
}
