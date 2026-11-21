package com.securedoc.platform.scanbridge.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025: the bridge's code table matches the design ({@code docs/work/lv-error-codes.md}), code and title. */
class BridgeLvCodeDocumentTest {

    static Path findDesignDocument() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs").resolve("work").resolve("lv-error-codes.md");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        return fail("docs/work/lv-error-codes.md not found above " + Path.of("").toAbsolutePath());
    }

    static Map<String, String> designTitles() throws Exception {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String line : Files.readAllLines(findDesignDocument(), StandardCharsets.UTF_8)) {
            if (!line.startsWith("| LV-")) continue;
            String[] cells = line.split("\\|");
            String code = cells[1].trim();
            String title = cells[2].trim();
            assertNull(titles.put(code, title), "the design lists " + code + " twice");
        }
        return titles;
    }

    @Test
    void everyBridgeCodeIsInTheDesignWithTheSameTitle() throws Exception {
        Map<String, String> design = designTitles();
        assertTrue(design.size() >= 100, "the design table was read (" + design.size() + " codes)");
        for (BridgeLvCode lv : BridgeLvCode.values()) {
            assertTrue(design.containsKey(lv.code()), lv.code() + " is not in docs/work/lv-error-codes.md");
            assertEquals(design.get(lv.code()), lv.title(), "title of " + lv.code());
            assertEquals(lv.name(), lv.code().replace('-', '_'), "enum name follows the code");
        }
    }

    @Test
    void theBridgeHoldsEveryScannerCodeOfTheDesign() throws Exception {
        Set<String> bridge = new HashSet<>();
        for (BridgeLvCode lv : BridgeLvCode.values()) bridge.add(lv.code());
        for (String code : designTitles().keySet()) {
            if (code.startsWith("LV-SCN-")) assertTrue(bridge.contains(code), code + " is missing from BridgeLvCode");
        }
    }
}
