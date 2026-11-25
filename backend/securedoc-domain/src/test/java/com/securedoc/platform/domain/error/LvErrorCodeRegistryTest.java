package com.securedoc.platform.domain.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0025 step 1: the registry and the design ({@code docs/work/lv-error-codes.md}) can never drift. Every code
 * row of the design's tables is read and compared field by field with {@link LvErrorCode}.
 */
class LvErrorCodeRegistryTest {

    private static final Pattern CODE_ROW = Pattern.compile("^\\| (LV-[A-Z]+-\\d{3}) \\|.*");
    private static final Pattern RESERVED = Pattern.compile("\\s*\\*\\(Reserved[^)]*\\)\\*\\s*$");
    private static final Map<String, LvErrorCode.Kind> KINDS = Map.of(
            "Refused", LvErrorCode.Kind.REFUSED, "Warning", LvErrorCode.Kind.WARNING,
            "Waiting", LvErrorCode.Kind.WAITING, "Stopped", LvErrorCode.Kind.STOPPED,
            "Export failed", LvErrorCode.Kind.EXPORT_FAILED, "Not allowed", LvErrorCode.Kind.NOT_ALLOWED,
            "Set-up", LvErrorCode.Kind.SET_UP, "System", LvErrorCode.Kind.SYSTEM);

    record DesignRow(String code, String title, String description, LvErrorCode.Kind kind, String whoActs,
                     List<Integer> http, boolean reserved) {
    }

    static Path design() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/work/lv-error-codes.md");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("docs/work/lv-error-codes.md not found above " + Path.of("").toAbsolutePath());
    }

    static Map<String, DesignRow> designRows() throws IOException {
        Map<String, DesignRow> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(design(), StandardCharsets.UTF_8)) {
            Matcher m = CODE_ROW.matcher(line);
            if (!m.matches()) continue;
            String[] raw = line.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (int i = 1; i < raw.length - 1; i++) cells.add(raw[i].trim());
            String description = cells.get(2);
            boolean reserved = RESERVED.matcher(description).find();
            description = RESERVED.matcher(description).replaceAll("").trim();
            LvErrorCode.Kind kind = KINDS.get(cells.get(3));
            assertTrue(kind != null, "unknown kind in the design: " + cells.get(3));
            // Task 0026: a cell can hold more than one status ("404 / 405" for LV-SYS-009).
            List<Integer> http = "—".equals(cells.get(5)) ? List.of()
                    : Arrays.stream(cells.get(5).split("/")).map(String::trim).map(Integer::valueOf).toList();
            DesignRow previous = rows.put(cells.get(0), new DesignRow(cells.get(0), cells.get(1), description, kind,
                    cells.get(4), http, reserved));
            assertTrue(previous == null, "the design lists " + cells.get(0) + " twice");
        }
        return rows;
    }

    @Test
    void registryHasExactlyTheDesignsCodesInOrder() throws IOException {
        List<String> design = new ArrayList<>(designRows().keySet());
        List<String> registry = Arrays.stream(LvErrorCode.values()).map(LvErrorCode::code).toList();
        assertEquals(design, registry);
    }

    @Test
    void everyFieldMatchesTheDesign() throws IOException {
        Map<String, DesignRow> design = designRows();
        for (LvErrorCode code : LvErrorCode.values()) {
            DesignRow row = design.get(code.code());
            assertEquals(row.title(), code.title(), code.code() + " title");
            assertEquals(row.description(), code.description(), code.code() + " description");
            assertEquals(row.kind(), code.kind(), code.code() + " kind");
            assertEquals(row.whoActs(), code.whoActs(), code.code() + " who acts");
            assertEquals(row.http(), code.httpStatuses(), code.code() + " HTTP status");
            assertEquals(row.http().isEmpty() ? null : row.http().get(0), code.httpStatus().orElse(null),
                    code.code() + " first HTTP status");
            assertEquals(row.reserved(), code.reserved(), code.code() + " reserved");
        }
    }

    @Test
    void constantNameAreaAndNumberFollowTheCode() {
        for (LvErrorCode code : LvErrorCode.values()) {
            assertEquals("LV-" + code.name().replace('_', '-'), code.code());
            assertTrue(code.code().startsWith("LV-" + code.area().name() + "-"), code.code());
        }
    }

    @Test
    void reservedCodesAreTheTwoLeftOfTheThreeTheDesignNames() {
        List<String> reserved = Arrays.stream(LvErrorCode.values()).filter(LvErrorCode::reserved)
                .map(LvErrorCode::code).toList();
        assertEquals(List.of("LV-DLV-009", "LV-DLV-015"), reserved);
    }

    @Test
    void fallbackIsSomethingWentWrong() {
        assertEquals("LV-SYS-001", LvErrorCode.FALLBACK.code());
        assertFalse(LvErrorCode.FALLBACK.reserved());
        assertEquals("Destination busy or down · LV-DLV-002", LvErrorCode.DLV_002.titleWithCode());
    }

    @Test
    void lookupByCode() {
        assertEquals(LvErrorCode.AUT_002, LvErrorCode.fromCode(" LV-AUT-002 ").orElseThrow());
        assertTrue(LvErrorCode.fromCode("LV-AUT-999").isEmpty());
        assertTrue(LvErrorCode.fromCode(null).isEmpty());
        assertEquals(42, LvErrorCode.inArea(LvErrorCode.Area.INT).size()); // task 0038: + LV-INT-028, 029; task 0063: 036, 037
    }

    @Test
    void areaCountsMatchTheDesignTables() {
        Map<LvErrorCode.Area, Integer> expected = new LinkedHashMap<>();
        expected.put(LvErrorCode.Area.INT, 42);
        expected.put(LvErrorCode.Area.SCN, 22); // task 0061: + LV-SCN-022
        expected.put(LvErrorCode.Area.PRC, 10); // task 0053: + LV-PRC-010
        expected.put(LvErrorCode.Area.AI, 8);
        expected.put(LvErrorCode.Area.RED, 5); // task 0044: + LV-RED-005
        expected.put(LvErrorCode.Area.REV, 15); // task 0071: + LV-REV-010 to 014; task 0076: + LV-REV-015
        expected.put(LvErrorCode.Area.DLV, 15);
        expected.put(LvErrorCode.Area.AUT, 10);
        expected.put(LvErrorCode.Area.LIC, 4);
        expected.put(LvErrorCode.Area.CFG, 15); // task 0061: + LV-CFG-012 to 015
        expected.put(LvErrorCode.Area.SYS, 15); // task 0060: + LV-SYS-010 to 014; task 0055: + LV-SYS-015
        expected.forEach((area, count) -> assertEquals(count, LvErrorCode.inArea(area).size(), area.name()));
        // Task 0026: 129 codes after 0025, plus the 8 added on 2026-11-21; task 0038 adds LV-INT-028 and 029.
        assertEquals(161, LvErrorCode.values().length); // task 0055: + LV-SYS-015; // task 0060: + LV-SYS-010 to 014; // task 0044: + LV-RED-005; task 0053: + LV-PRC-010; 0063: + LV-INT-036, 037; 0064: + LV-AI-008
    }
}
