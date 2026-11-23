package com.securedoc.platform.application.port.out;

import java.util.function.Supplier;

/**
 * Task 0048: runs work in its own database transaction. Everything the work saves is committed together, or, when it
 * throws, nothing is.
 */
@FunctionalInterface
public interface TransactionPort {

    <T> T inNewTransaction(Supplier<T> work);

    /** For tests without a database: runs the work as it is. */
    TransactionPort DIRECT = new TransactionPort() {
        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            return work.get();
        }
    };
}
