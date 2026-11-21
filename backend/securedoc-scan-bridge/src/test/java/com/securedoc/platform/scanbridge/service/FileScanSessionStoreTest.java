package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023: what the bridge's own store says when it fails, and how it deletes. */
class FileScanSessionStoreTest {
    @TempDir Path temp;

    @Test
    void anUnreadableStateFileIsNamedByItsSessionIdOnlyWithoutPathOrContent() throws Exception {
        Path workRoot = temp.resolve("Jane Doe").resolve("work");
        UUID sessionId = UUID.randomUUID();
        Path dir = workRoot.resolve(sessionId.toString());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FileScanSessionStore.STATE_FILE), "{\"contract\": 000900009901 broken");
        ScanBridgeProperties props = new ScanBridgeProperties();
        props.setWorkPath(workRoot.toString());
        FileScanSessionStore store = new FileScanSessionStore(props, new ObjectMapper().findAndRegisterModules());

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::loadAll);

        assertTrue(ex.getMessage().contains(sessionId.toString()));
        assertFalse(ex.getMessage().contains("Jane"));
        assertNull(ex.getCause(), "a parser message can quote the state content");
    }

    @Test
    void bestEffortTreeDeleteRemovesEverythingItCan() throws Exception {
        Path root = temp.resolve("capture");
        Files.createDirectories(root.resolve("a").resolve("b"));
        Files.writeString(root.resolve("page-1.bmp"), "x");
        Files.writeString(root.resolve("a").resolve("b").resolve("page-2.bmp"), "y");

        assertEquals(0, FileScanSessionStore.deleteTreeBestEffort(root));
        assertFalse(Files.exists(root));
        assertEquals(0, FileScanSessionStore.deleteTreeBestEffort(root), "a missing folder is nothing to do");
    }
}
