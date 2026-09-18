package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;

/** Hardware/simulator acquisition boundary. Downstream queue and delivery are mode-independent. */
public interface ScannerAcquisitionAdapter {
    ScannerCaptureMode mode();
    boolean available();
    void assertReadyForNewSession();

    /**
     * Called only after a durable scan session exists. Simulator acquisition is browser-staged and
     * therefore uses the default no-op. Physical adapters acquire the document and mark the session READY.
     */
    default void acquire(ScanSession session) {
        // no-op for acquisition modes that receive the document asynchronously
    }
}
