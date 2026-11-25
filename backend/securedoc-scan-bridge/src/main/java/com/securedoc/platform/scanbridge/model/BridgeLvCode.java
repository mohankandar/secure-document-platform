package com.securedoc.platform.scanbridge.model;

/**
 * Task 0025: the LexaVault error codes ({@code LV-<AREA>-<NNN>}) the Scan Bridge gives, with their titles.
 *
 * <p>The source of truth is {@code docs/work/lv-error-codes.md}; {@code BridgeLvCodeDocumentTest} fails if a code
 * here is missing from it or its title differs. The bridge does not depend on the domain module (where the full
 * registry lives), so it keeps this small copy of the codes it uses. Which bridge {@code errorCode} gives which LV
 * code is decided in one place: {@link BridgeLvCodes}.</p>
 */
public enum BridgeLvCode {
    LV_SCN_001("LV-SCN-001", "Scan must be one document"),
    LV_SCN_002("LV-SCN-002", "Scanned file size doesn't match"),
    LV_SCN_003("LV-SCN-003", "Scanner details missing"),
    LV_SCN_004("LV-SCN-004", "Empty scan or not a PDF"),
    LV_SCN_005("LV-SCN-005", "Scan too large or disk full"),
    LV_SCN_006("LV-SCN-006", "Scanner busy"),
    LV_SCN_007("LV-SCN-007", "Scan Bridge switched off or in the wrong mode"),
    LV_SCN_008("LV-SCN-008", "Scanner not found"),
    LV_SCN_009("LV-SCN-009", "Scan didn't finish"),
    LV_SCN_010("LV-SCN-010", "Scan session not found or not ready"),
    /** Given by the browser when it cannot reach the bridge; the bridge itself never sends it. */
    LV_SCN_011("LV-SCN-011", "Scan Bridge not running"),
    LV_SCN_012("LV-SCN-012", "Scan Bridge refused the request"),
    LV_SCN_013("LV-SCN-013", "Scan Bridge can't save its state"),
    LV_SCN_014("LV-SCN-014", "Scan Bridge can't start"),
    LV_SCN_015("LV-SCN-015", "LexaVault can't be reached from the scanner"),
    LV_SCN_016("LV-SCN-016", "LexaVault refused the scan"),
    LV_SCN_017("LV-SCN-017", "Scan waiting too long for LexaVault"),
    LV_SCN_018("LV-SCN-018", "Scan couldn't be sent"),
    LV_SCN_019("LV-SCN-019", "Scan can't be cancelled now"),
    LV_SCN_020("LV-SCN-020", "Scan resumed after a restart"),
    LV_SCN_021("LV-SCN-021", "Waiting for LexaVault to accept the scan"),
    LV_SCN_022("LV-SCN-022", "Scan needs its sensitive fields again"),
    LV_INT_015("LV-INT-015", "Case field value not valid"),
    LV_CFG_009("LV-CFG-009", "Scanner settings not valid"),
    LV_SYS_001("LV-SYS-001", "Something went wrong"),
    LV_SYS_007("LV-SYS-007", "Temporary file left behind");

    private final String code;
    private final String title;

    BridgeLvCode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    /** The code as people and logs see it, for example {@code LV-SCN-015}. */
    public String code() { return code; }

    /** The plain message for the code, exactly as in the design. */
    public String title() { return title; }
}
