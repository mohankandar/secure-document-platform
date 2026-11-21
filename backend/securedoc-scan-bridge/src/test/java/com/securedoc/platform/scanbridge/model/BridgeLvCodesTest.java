package com.securedoc.platform.scanbridge.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.securedoc.platform.scanbridge.model.BridgeLvCode.*;
import static com.securedoc.platform.scanbridge.model.ScanSessionStatus.ATTENTION_REQUIRED;
import static com.securedoc.platform.scanbridge.model.ScanSessionStatus.QUEUED;
import static org.junit.jupiter.api.Assertions.*;

/** Task 0025: every bridge errorCode gives an LV code, from one place. */
class BridgeLvCodesTest {

    /** Mapped to LV-SYS-001 on purpose: an unexpected error (#78), or a failure the design places under no code. */
    private static final Set<String> INTENTIONAL_SYS_001 = Set.of(
            "BRIDGE_INTERNAL_ERROR",                // #78
            "SUBMISSION_ALREADY_QUEUED",            // #92, not placed by the design
            "ATTENTION_REQUIRED_CANNOT_CANCEL",     // #92, not placed by the design
            "RECOVERED_AFTER_RESTART");             // back in the queue after a restart; not a failure in the design

    /** Upper-case literals in the bridge's main code that are not error codes. */
    private static final Set<String> NOT_ERROR_CODES = Set.of(
            "ACCEPTED_FOR_PROCESSING", "INCOMPLETE_EXPIRED",                       // LexaVault intake statuses
            "AI_EXTRACTION", "BARCODE_EXTRACTION", "BUSINESS_METADATA_INFERENCE",  // configuration response values
            "DOCUMENT_CLASSIFICATION", "QR_EXTRACTION",
            "AUTO", "FEEDER", "FLATBED",                                          // scanner sources
            "DELETE", "PATCH", "POST", "OPTIONS",                                 // HTTP methods
            "SCANNER");                                                           // intake channel
    /** Log event names and markers printed by the WIA helper script; never an errorCode. */
    private static final List<String> NOT_ERROR_CODE_PREFIXES = List.of("SCAN_BRIDGE_", "LEXAVAULT_WIA_");

    private static final Pattern LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{3,})\"");

    static Set<String> upperCaseLiteralsInMainCode() throws Exception {
        Path root = Path.of("src", "main", "java", "com", "securedoc", "platform", "scanbridge").toAbsolutePath();
        assertTrue(Files.isDirectory(root), "bridge sources not found at " + root);
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                // The code table and the mapping themselves hold the identifiers as keys.
                if (name.equals("BridgeLvCodes.java") || name.equals("BridgeLvCode.java")) continue;
                Matcher m = LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) found.add(m.group(1));
            }
        }
        return found;
    }

    @Test
    void everyErrorCodeTheBridgeProducesHasAnLvCode() throws Exception {
        Set<String> literals = upperCaseLiteralsInMainCode();
        assertTrue(literals.contains("SCANNER_BUSY") && literals.contains("LEXAVAULT_UNAVAILABLE"),
                "the source scan found the bridge's codes: " + literals);
        Set<String> unmapped = new TreeSet<>();
        Set<String> fallsBack = new TreeSet<>();
        for (String literal : literals) {
            if (NOT_ERROR_CODES.contains(literal)) continue;
            if (NOT_ERROR_CODE_PREFIXES.stream().anyMatch(literal::startsWith)) continue;
            if (!BridgeLvCodes.isMapped(literal)) {
                unmapped.add(literal);
            } else if (BridgeLvCodes.forBridgeCode(literal) == LV_SYS_001 && !INTENTIONAL_SYS_001.contains(literal)) {
                fallsBack.add(literal);
            }
        }
        assertEquals(Set.of(), unmapped, "add these to BridgeLvCodes (or to NOT_ERROR_CODES if they are not error codes)");
        assertEquals(Set.of(), fallsBack, "these give LV-SYS-001 without being listed as intentional");
    }

    @Test
    void theIntentionalFallbacksAreMappedExplicitly() {
        for (String code : INTENTIONAL_SYS_001) {
            assertTrue(BridgeLvCodes.isMapped(code), code);
            assertEquals(LV_SYS_001, BridgeLvCodes.forBridgeCode(code), code);
        }
    }

    @Test
    void theMappingFollowsTheDesignRows() {
        assertEquals(LV_SCN_004, BridgeLvCodes.forBridgeCode("EMPTY_SCAN"));
        assertEquals(LV_SCN_004, BridgeLvCodes.forBridgeCode("INVALID_PDF"));
        assertEquals(LV_SCN_005, BridgeLvCodes.forBridgeCode("SCAN_PDF_TOO_LARGE"));
        assertEquals(LV_SCN_005, BridgeLvCodes.forBridgeCode("SCANNER_STORAGE_UNAVAILABLE"));
        assertEquals(LV_SCN_006, BridgeLvCodes.forBridgeCode("SCANNER_BUSY"));
        assertEquals(LV_SCN_006, BridgeLvCodes.forBridgeCode("SCANNER_INBOX_NOT_EMPTY"));
        assertEquals(LV_SCN_007, BridgeLvCodes.forBridgeCode("BRIDGE_DISABLED"));
        assertEquals(LV_SCN_007, BridgeLvCodes.forBridgeCode("SIMULATOR_NOT_ENABLED"));
        assertEquals(LV_SCN_008, BridgeLvCodes.forBridgeCode("PHYSICAL_SCANNER_NOT_AVAILABLE"));
        assertEquals(LV_SCN_009, BridgeLvCodes.forBridgeCode("PHYSICAL_SCANNER_TIMEOUT"));
        assertEquals(LV_SCN_010, BridgeLvCodes.forBridgeCode("SCAN_SESSION_NOT_FOUND"));
        assertEquals(LV_SCN_010, BridgeLvCodes.forBridgeCode("LOCAL_SCAN_ARTIFACT_MISSING"), "#95 retry refused");
        assertEquals(LV_SCN_012, BridgeLvCodes.forBridgeCode("BRIDGE_ORIGIN_NOT_ALLOWED"));
        assertEquals(LV_SCN_012, BridgeLvCodes.forBridgeCode("BRIDGE_REQUEST_TOKEN_INVALID"));
        assertEquals(LV_SCN_013, BridgeLvCodes.forBridgeCode("BRIDGE_LOCAL_STATE_FAILURE"));
        assertEquals(LV_SCN_014, BridgeLvCodes.forBridgeCode("LEXAVAULT_ENDPOINT_NOT_CONFIGURED"));
        assertEquals(LV_SCN_015, BridgeLvCodes.forBridgeCode("LEXAVAULT_UNAVAILABLE"));
        assertEquals(LV_SCN_017, BridgeLvCodes.forBridgeCode("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED"));
        assertEquals(LV_INT_015, BridgeLvCodes.forBridgeCode("INVALID_BUSINESS_IDENTIFIER_INPUT"));
        assertEquals(LV_CFG_009, BridgeLvCodes.forBridgeCode("PHYSICAL_SCANNER_DPI_INVALID"));
        assertEquals(LV_SYS_001, BridgeLvCodes.forBridgeCode("BRIDGE_INTERNAL_ERROR"));
    }

    @Test
    void anUnknownCodeFallsBackToSys001() {
        assertFalse(BridgeLvCodes.isMapped("SOMETHING_NEW"));
        assertEquals(LV_SYS_001, BridgeLvCodes.forBridgeCode("SOMETHING_NEW"));
        assertEquals(LV_SYS_001, BridgeLvCodes.forBridgeCode("Contract 12345 for Jane Doe"));
        assertEquals(LV_SYS_001, BridgeLvCodes.forBridgeCode(null));
    }

    @Test
    void aLexaVaultHttpCodeIsAnAnswerFromLexaVault() {
        assertEquals(LV_SCN_015, BridgeLvCodes.forBridgeCode("LEXAVAULT_HTTP_503"));
        assertEquals(LV_SCN_015, BridgeLvCodes.forBridgeCode("LEXAVAULT_HTTP_429"));
        assertEquals(LV_SCN_016, BridgeLvCodes.forBridgeCode("LEXAVAULT_HTTP_400"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 408, 409, 413, 422, 425})
    void aLexaVaultRefusalIsScn016WhateverTheState(int status) {
        assertEquals(LV_SCN_016, BridgeLvCodes.forDelivery("INTAKE_UNKNOWN_IDENTIFIER", status, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_016, BridgeLvCodes.forDelivery("INTAKE_UNKNOWN_IDENTIFIER", status, QUEUED),
                "after a manual retry the item is queued again with LexaVault's code");
        assertEquals(LV_SCN_016, BridgeLvCodes.forDelivery("LEXAVAULT_HTTP_" + status, null, ATTENTION_REQUIRED));
    }

    @Test
    void aLexaVaultCodeThatCollidesWithABridgeCodeIsStillAnAnswerWhenTheStatusIsKnown() {
        assertEquals(LV_SCN_016, BridgeLvCodes.forDelivery("SCANNER_BUSY", 409, ATTENTION_REQUIRED));
    }

    @Test
    void transientFailuresAreScn015WhileRetriedAndScn018OnceRetriesRanOut() {
        for (String code : List.of("LEXAVAULT_UNAVAILABLE", "LEXAVAULT_REQUEST_INTERRUPTED", "LEXAVAULT_INVALID_RESPONSE",
                "LEXAVAULT_HTTP_500", "LEXAVAULT_HTTP_503", "LEXAVAULT_HTTP_429")) {
            assertEquals(LV_SCN_015, BridgeLvCodes.forDelivery(code, null, QUEUED), code);
            assertEquals(LV_SCN_018, BridgeLvCodes.forDelivery(code, null, ATTENTION_REQUIRED), code);
        }
        assertEquals(LV_SCN_015, BridgeLvCodes.forDelivery("RATE_LIMITED", 429, QUEUED));
        assertEquals(LV_SCN_018, BridgeLvCodes.forDelivery("RATE_LIMITED", 429, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_018, BridgeLvCodes.forDelivery("INTERNAL_ERROR", 500, ATTENTION_REQUIRED));
    }

    @Test
    void deliveryCodesOfTheBridgeItself() {
        assertNull(BridgeLvCodes.forDelivery(null, null, QUEUED));
        assertEquals(LV_SCN_015, BridgeLvCodes.forDelivery("LEXAVAULT_ACCEPTANCE_PENDING", null, QUEUED));
        assertEquals(LV_SCN_017, BridgeLvCodes.forDelivery("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED", null, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_015, BridgeLvCodes.forDelivery("LEXAVAULT_REQUEST_SERIALIZATION_FAILED", null, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_013, BridgeLvCodes.forDelivery("LOCAL_SCAN_ARTIFACT_MISSING", null, ATTENTION_REQUIRED), "#90");
        assertEquals(LV_SCN_013, BridgeLvCodes.forDelivery("LEXAVAULT_PACKAGE_RECONCILIATION_MISMATCH", null, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_013, BridgeLvCodes.forDelivery("SCANNER_DELIVERY_INTERNAL_ERROR", null, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_014, BridgeLvCodes.forDelivery("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", null, ATTENTION_REQUIRED));
    }

    @Test
    void aLexaVaultCodeFromAnOlderStateFileWithoutStatus() {
        assertEquals(LV_SCN_016, BridgeLvCodes.forDelivery("ACCESS_DENIED", null, ATTENTION_REQUIRED));
        assertEquals(LV_SCN_015, BridgeLvCodes.forDelivery("ACCESS_DENIED", null, QUEUED));
    }

    @Test
    void onlyAnLvShapedLexaVaultCodeIsKept() {
        assertEquals("LV-INT-014", BridgeLvCodes.safeLexaVaultLvCode(" LV-INT-014 "));
        assertNull(BridgeLvCodes.safeLexaVaultLvCode("Contract 12345 for Jane Doe"));
        assertNull(BridgeLvCodes.safeLexaVaultLvCode("LV-INT-014 Jane"));
        assertNull(BridgeLvCodes.safeLexaVaultLvCode(null));
    }
}
