package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvErrorCode;

/** Task 0067: LexaVault takes nothing in for now (too little space); the sender sends again after the time given. */
public final class IntakeBusyException extends ApplicationException {
    private final int retryAfterSeconds;

    public IntakeBusyException(int retryAfterSeconds) {
        super("INTAKE_BUSY", "LexaVault is full for now", LvErrorCode.INT_027);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
