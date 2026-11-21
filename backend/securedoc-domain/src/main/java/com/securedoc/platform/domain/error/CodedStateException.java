package com.securedoc.platform.domain.error;

import java.util.Objects;

/**
 * Task 0025: a state rule refused an action and knows its LexaVault code (for example "Case is no longer in review"
 * is {@link LvErrorCode#REV_002}). It is an {@link IllegalStateException}, so code that catches those behaves exactly
 * as before; only the code and the words people see are added. The message stays LexaVault's own text for the log
 * and is never sent to the person (the code's title is).
 */
public class CodedStateException extends IllegalStateException implements LvCoded {

    private final LvErrorCode lvCode;

    public CodedStateException(LvErrorCode lvCode, String message) {
        super(message);
        this.lvCode = Objects.requireNonNull(lvCode, "lvCode");
    }

    @Override
    public LvErrorCode lvCode() {
        return lvCode;
    }
}
