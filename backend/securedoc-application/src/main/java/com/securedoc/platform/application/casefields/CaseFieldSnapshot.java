package com.securedoc.platform.application.casefields;

import com.securedoc.platform.domain.identity.BusinessRecordId;

import java.util.List;
import java.util.Objects;

/** A business record's case fields for a screen. A sensitive value is {@link #HIDDEN} unless it was asked for. */
public record CaseFieldSnapshot(BusinessRecordId businessRecordId, boolean createdNew, List<Value> values) {
    /** Shown in place of a sensitive value. */
    public static final String HIDDEN = "•".repeat(6);

    public CaseFieldSnapshot {
        Objects.requireNonNull(businessRecordId);
        values = List.copyOf(values);
    }

    /** The value is in no text form of this. */
    public record Value(String key, String label, String value, boolean primary, boolean searchable, boolean displayable,
                        int displayOrder, boolean sensitive) {
        @Override
        public String toString() {
            return "Value[" + key + "]";
        }
    }
}
