package com.securedoc.platform.domain.review;

import com.securedoc.platform.domain.error.LvErrorCode;

import java.util.Arrays;
import java.util.Optional;

/** Task 0071: why a person rejected a case. A fixed list, stored and shown by its LV code; never free text. */
public enum RejectionReason {
    WRONG_DOCUMENT(LvErrorCode.REV_010),
    UNREADABLE(LvErrorCode.REV_011),
    OUT_OF_SCOPE(LvErrorCode.REV_012),
    DUPLICATE(LvErrorCode.REV_013),
    OTHER_SEE_TICKET(LvErrorCode.REV_014);

    private final LvErrorCode code;

    RejectionReason(LvErrorCode code) {
        this.code = code;
    }

    public LvErrorCode code() {
        return code;
    }

    public static Optional<RejectionReason> fromCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code.code().equals(code)).findFirst();
    }
}
