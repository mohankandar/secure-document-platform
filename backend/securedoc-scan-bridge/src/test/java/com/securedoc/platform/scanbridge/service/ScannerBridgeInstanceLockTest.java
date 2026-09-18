package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScannerBridgeInstanceLockTest {
    @TempDir Path temp;

    @Test
    void onlyOneBridgeProcessMayOwnTheSameDurableWorkRoot() {
        ScanBridgeProperties properties = new ScanBridgeProperties();
        properties.setWorkPath(temp.resolve("work").toString());
        ScannerBridgeInstanceLock first = new ScannerBridgeInstanceLock(properties);
        ScannerBridgeInstanceLock second = new ScannerBridgeInstanceLock(properties);

        first.acquire();
        try {
            assertThrows(IllegalStateException.class, second::acquire);
        } finally {
            first.release();
        }

        try {
            assertDoesNotThrow(second::acquire);
        } finally {
            second.release();
        }
    }
}
