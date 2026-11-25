package com.securedoc.platform.application.port.out;

/**
 * Task 0060: whether this installation may take work now. After a restore from a backup it may not (no intake, no
 * processing, no export, no change by a person) until the erase replay has run and was verified.
 */
public interface WorkGate {
    boolean mayTakeWork();

    /** @throws com.securedoc.platform.application.exception.TechnicalDependencyException LV-SYS-013 or LV-SYS-014 */
    void requireWork();

    static WorkGate open() {
        return new WorkGate() {
            @Override
            public boolean mayTakeWork() {
                return true;
            }

            @Override
            public void requireWork() {
            }
        };
    }
}
