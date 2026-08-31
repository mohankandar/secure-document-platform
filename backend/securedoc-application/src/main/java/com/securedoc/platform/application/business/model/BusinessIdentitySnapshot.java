package com.securedoc.platform.application.business.model;

import com.securedoc.platform.domain.identity.BusinessRecordId;
import java.util.List;
import java.util.Objects;

public record BusinessIdentitySnapshot(BusinessRecordId businessRecordId, boolean createdNew,
                                       List<IdentifierValue> identifiers) {
    public BusinessIdentitySnapshot {
        Objects.requireNonNull(businessRecordId);
        identifiers = List.copyOf(identifiers);
    }
    public record IdentifierValue(String key, String label, String value, boolean primary, boolean searchable,
                                  boolean displayable, int displayOrder) {}
}
