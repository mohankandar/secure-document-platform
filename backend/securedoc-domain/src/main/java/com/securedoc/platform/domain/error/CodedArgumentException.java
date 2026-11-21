package com.securedoc.platform.domain.error;

import java.util.Objects;

/**
 * Task 0025: a value was refused and the refusal knows its LexaVault code (for example a redaction box outside the
 * page is {@link LvErrorCode#REV_006}). It is an {@link IllegalArgumentException}, so code that catches those behaves
 * exactly as before. The message is never sent to the person (the code's title is).
 */
public class CodedArgumentException extends IllegalArgumentException implements LvCoded {

    private final LvErrorCode lvCode;

    public CodedArgumentException(LvErrorCode lvCode, String message) {
        super(message);
        this.lvCode = Objects.requireNonNull(lvCode, "lvCode");
    }

    @Override
    public LvErrorCode lvCode() {
        return lvCode;
    }
}
