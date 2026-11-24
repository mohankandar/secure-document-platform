package com.securedoc.platform.application.intake;

import com.securedoc.platform.application.exception.IntakeBusyException;

/** Task 0067: how much intake may take now. Files LexaVault does not take wait at their source; nothing is lost. */
public interface IntakeGate {

    /** Takes everything (tests of intake itself). */
    IntakeGate OPEN = new IntakeGate() {
        @Override
        public int slowdown() {
            return 1;
        }

        @Override
        public boolean full() {
            return false;
        }

        @Override
        public int retryAfterSeconds() {
            return 0;
        }
    };

    /** 1 while the queue is short; 2, 4 or 8 the longer it is: checked that many times less often, that much less each. */
    int slowdown();

    /** The safety stop: too little disk or database space is free. */
    boolean full();

    int retryAfterSeconds();

    default void requireSpace() {
        if (full()) throw new IntakeBusyException(retryAfterSeconds());
    }

    /** The most one check takes of {@code usual}; at least one. */
    default int share(int usual) {
        return Math.max(1, usual / slowdown());
    }
}
