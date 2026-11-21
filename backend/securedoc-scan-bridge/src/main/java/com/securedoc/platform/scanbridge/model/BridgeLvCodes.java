package com.securedoc.platform.scanbridge.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0025: the one place that decides which LV code a Scan Bridge {@code errorCode} gives.
 *
 * <p>Rows ({@code #n}) refer to {@code docs/work/0022-failure-inventory.md}; the codes and rows come from
 * {@code docs/work/lv-error-codes.md}. Today's {@code errorCode} stays as the detail. A code with no mapping gives
 * {@link BridgeLvCode#LV_SYS_001} and is logged once by its identifier (never message text), so a gap is visible.</p>
 */
public final class BridgeLvCodes {
    private static final Logger log = LoggerFactory.getLogger(BridgeLvCodes.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern LV_CODE = Pattern.compile("LV-[A-Z]{2,4}-[0-9]{3}");
    private static final Pattern LEXAVAULT_HTTP = Pattern.compile("LEXAVAULT_HTTP_([1-5][0-9]{2})");
    /** The prefix of the code the delivery client gives when LexaVault's answer has no usable errorCode. */
    public static final String LEXAVAULT_HTTP_PREFIX = "LEXAVAULT_HTTP_";
    private static final int MAX_LOGGED_GAPS = 256;
    private static final Set<String> loggedGaps = ConcurrentHashMap.newKeySet();

    private static final Map<String, BridgeLvCode> CODES;
    /** Delivery codes that mean "LexaVault could not be reached or answered badly"; retried by the worker. */
    private static final Set<String> TRANSIENT_DELIVERY_CODES = Set.of(
            "LEXAVAULT_UNAVAILABLE", "LEXAVAULT_REQUEST_INTERRUPTED", "LEXAVAULT_INVALID_RESPONSE");
    /** The same identifier means something else when it is a delivery item's code (row #90) than a refusal (#95). */
    /** Delivery item codes that describe the item's state rather than a failure. */
    private static final Set<String> STATE_CODES = Set.of("LEXAVAULT_ACCEPTANCE_PENDING", "RECOVERED_AFTER_RESTART");
    private static final Map<String, BridgeLvCode> DELIVERY_OVERRIDES = Map.of(
            "LOCAL_SCAN_ARTIFACT_MISSING", BridgeLvCode.LV_SCN_013);

    static {
        Map<String, BridgeLvCode> m = new LinkedHashMap<>();
        // #76, #86: test PDF or inbox file empty, not a PDF, unreadable, or an interrupted upload.
        m.put("EMPTY_SCAN", BridgeLvCode.LV_SCN_004);
        m.put("UNSUPPORTED_FILE_TYPE", BridgeLvCode.LV_SCN_004);
        m.put("SIMULATOR_STAGE_FAILED", BridgeLvCode.LV_SCN_004);
        m.put("INVALID_PDF", BridgeLvCode.LV_SCN_004);
        m.put("INCOMPLETE_SIMULATOR_UPLOAD", BridgeLvCode.LV_SCN_004);
        // #77: local state cannot be saved or read.
        m.put("BRIDGE_LOCAL_STATE_FAILURE", BridgeLvCode.LV_SCN_013);
        // #78: any other bridge request failure.
        m.put("BRIDGE_INTERNAL_ERROR", BridgeLvCode.LV_SYS_001);
        // #79, #80, #275: origin not allowed, request token missing or stale.
        m.put("BRIDGE_ORIGIN_NOT_ALLOWED", BridgeLvCode.LV_SCN_012);
        m.put("BRIDGE_REQUEST_TOKEN_INVALID", BridgeLvCode.LV_SCN_012);
        // #82, #276: LexaVault unreachable, interrupted, bad answer, request not serialisable.
        m.put("LEXAVAULT_UNAVAILABLE", BridgeLvCode.LV_SCN_015);
        m.put("LEXAVAULT_REQUEST_INTERRUPTED", BridgeLvCode.LV_SCN_015);
        m.put("LEXAVAULT_INVALID_RESPONSE", BridgeLvCode.LV_SCN_015);
        m.put("LEXAVAULT_REQUEST_SERIALIZATION_FAILED", BridgeLvCode.LV_SCN_015);
        // Task 0026: sent, LexaVault has not confirmed it yet (still within the wait limit).
        m.put("LEXAVAULT_ACCEPTANCE_PENDING", BridgeLvCode.LV_SCN_021);
        // Task 0024: LexaVault did not accept within the wait limit.
        m.put("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED", BridgeLvCode.LV_SCN_017);
        // #84, #97: inbox already holds a PDF; a session already waiting.
        m.put("SCANNER_INBOX_NOT_EMPTY", BridgeLvCode.LV_SCN_006);
        // Task 0026: the inbox can't be listed is the bridge's working files, not a busy scanner.
        m.put("SCANNER_INBOX_UNAVAILABLE", BridgeLvCode.LV_SCN_013);
        m.put("SCANNER_BUSY", BridgeLvCode.LV_SCN_006);
        // #85, #97: wrong mode, bridge disabled.
        m.put("SIMULATOR_NOT_ENABLED", BridgeLvCode.LV_SCN_007);
        m.put("PHYSICAL_SCANNER_NOT_ENABLED", BridgeLvCode.LV_SCN_007);
        m.put("BRIDGE_DISABLED", BridgeLvCode.LV_SCN_007);
        // #86, #88: over the size limit, disk too full.
        m.put("SCAN_PDF_TOO_LARGE", BridgeLvCode.LV_SCN_005);
        m.put("SCANNER_STORAGE_UNAVAILABLE", BridgeLvCode.LV_SCN_005);
        // #90: delivery stopped by the bridge's own state. #314: LexaVault address not set.
        m.put("LEXAVAULT_PACKAGE_RECONCILIATION_MISMATCH", BridgeLvCode.LV_SCN_013);
        m.put("SCANNER_DELIVERY_INTERNAL_ERROR", BridgeLvCode.LV_SCN_013);
        m.put("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", BridgeLvCode.LV_SCN_014);
        // #92 (task 0026): cancel refused while queued or needing attention.
        m.put("SUBMISSION_ALREADY_QUEUED", BridgeLvCode.LV_SCN_019);
        m.put("ATTENTION_REQUIRED_CANNOT_CANCEL", BridgeLvCode.LV_SCN_019);
        // #93, #94, #95: session unknown or not waiting, scan not ready, nothing to retry.
        m.put("SCAN_SESSION_NOT_FOUND", BridgeLvCode.LV_SCN_010);
        m.put("SCAN_SESSION_NOT_WAITING", BridgeLvCode.LV_SCN_010);
        m.put("PHYSICAL_SCAN_SESSION_INVALID", BridgeLvCode.LV_SCN_010);
        m.put("SCAN_NOT_READY", BridgeLvCode.LV_SCN_010);
        m.put("SCAN_ARTIFACT_MISSING", BridgeLvCode.LV_SCN_010);
        m.put("DELIVERY_NOT_ATTENTION_REQUIRED", BridgeLvCode.LV_SCN_010);
        m.put("LOCAL_SCAN_ARTIFACT_MISSING", BridgeLvCode.LV_SCN_010);
        // #96: identifier key or value too long.
        m.put("INVALID_BUSINESS_IDENTIFIER_INPUT", BridgeLvCode.LV_INT_015);
        // #98: the scan job failed.
        m.put("PHYSICAL_SCANNER_TIMEOUT", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_CANCELLED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_MAX_PAGES_REACHED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCANNER_NO_PAGES", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_CAPTURE_DIRECTORY_NOT_EMPTY", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_ACQUISITION_FAILED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCANNER_IO_FAILED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_PDF_ASSEMBLY_FAILED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_FINALIZATION_FAILED", BridgeLvCode.LV_SCN_009);
        m.put("PHYSICAL_SCAN_ARTIFACT_ALREADY_EXISTS", BridgeLvCode.LV_SCN_009);
        // #99: no usable physical scanner.
        // Task 0026: the device isn't there is "Scanner not found", not "Scan didn't finish".
        m.put("PHYSICAL_SCANNER_DEVICE_NOT_FOUND", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_PROVIDER_UNSUPPORTED", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_WINDOWS_REQUIRED", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_NOT_AVAILABLE", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_POWERSHELL_UNAVAILABLE", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_HELPER_UNAVAILABLE", BridgeLvCode.LV_SCN_008);
        m.put("PHYSICAL_SCANNER_INTERRUPTED", BridgeLvCode.LV_SCN_008);
        // #315: physical scanner setting out of range.
        m.put("PHYSICAL_SCANNER_SOURCE_INVALID", BridgeLvCode.LV_CFG_009);
        m.put("PHYSICAL_SCANNER_DPI_INVALID", BridgeLvCode.LV_CFG_009);
        m.put("PHYSICAL_SCANNER_MAX_PAGES_INVALID", BridgeLvCode.LV_CFG_009);
        m.put("PHYSICAL_SCANNER_TIMEOUT_INVALID", BridgeLvCode.LV_CFG_009);
        // Task 0026: after a restart mid-delivery the item goes back to the queue (a warning).
        m.put("RECOVERED_AFTER_RESTART", BridgeLvCode.LV_SCN_020);
        CODES = Collections.unmodifiableMap(m);
    }

    private BridgeLvCodes() { }

    /** Every bridge identifier with an explicit mapping (for tests and the report). */
    public static Map<String, BridgeLvCode> mappings() { return CODES; }

    /** True when {@code errorCode} has its own mapping (a {@code LEXAVAULT_HTTP_<status>} code counts). */
    public static boolean isMapped(String errorCode) {
        return errorCode != null && (CODES.containsKey(errorCode) || LEXAVAULT_HTTP.matcher(errorCode).matches());
    }

    /**
     * The LV code for a bridge {@code errorCode} given in a refusal (the bridge's own error body). A
     * {@code LEXAVAULT_HTTP_<status>} code is a LexaVault answer: 5xx and 429 give LV-SCN-015, other statuses
     * LV-SCN-016. Anything else unknown gives LV-SYS-001 and is logged once.
     */
    public static BridgeLvCode forBridgeCode(String errorCode) {
        BridgeLvCode mapped = errorCode == null ? null : CODES.get(errorCode);
        if (mapped != null) return mapped;
        Integer status = statusOfLexaVaultHttpCode(errorCode);
        if (status != null) return lexaVaultAnswerIsTransient(status) ? BridgeLvCode.LV_SCN_015 : BridgeLvCode.LV_SCN_016;
        logGapOnce(errorCode);
        return BridgeLvCode.LV_SYS_001;
    }

    /**
     * The LV code for a delivery item's {@code lastDeliveryErrorCode}; null when there is none.
     *
     * <ul>
     *   <li>LexaVault answered (its HTTP status is known, or the code is {@code LEXAVAULT_HTTP_<status>}): a refusal
     *       (4xx other than 429) gives LV-SCN-016 (LexaVault's own code stays as the detail); a 5xx or 429 gives
     *       LV-SCN-015 while retries go on, and LV-SCN-018 once the item needs a person (the retries ran out).</li>
     *   <li>LexaVault unreachable, interrupted or answering badly: LV-SCN-015, and LV-SCN-018 once retries ran out.</li>
     *   <li>Other bridge codes: as in {@link #forBridgeCode(String)}, with the delivery meaning where it differs.</li>
     *   <li>A LexaVault code from a state file written before the HTTP status was kept: LV-SCN-016 when the item
     *       needs a person, LV-SCN-015 while it is still being retried.</li>
     * </ul>
     */
    public static BridgeLvCode forDelivery(String errorCode, Integer httpStatus, ScanSessionStatus status) {
        if (errorCode == null) return null;
        // Task 0026: a state, not LexaVault's answer: waiting for acceptance (LV-SCN-021), resumed after a restart
        // (LV-SCN-020). An HTTP status kept from an earlier attempt doesn't change that.
        if (STATE_CODES.contains(errorCode)) return CODES.get(errorCode);
        boolean needsPerson = status == ScanSessionStatus.ATTENTION_REQUIRED;
        Integer answered = httpStatus != null ? httpStatus : statusOfLexaVaultHttpCode(errorCode);
        if (answered != null) {
            if (!lexaVaultAnswerIsTransient(answered)) return BridgeLvCode.LV_SCN_016;
            return needsPerson ? BridgeLvCode.LV_SCN_018 : BridgeLvCode.LV_SCN_015;
        }
        if (TRANSIENT_DELIVERY_CODES.contains(errorCode)) {
            return needsPerson ? BridgeLvCode.LV_SCN_018 : BridgeLvCode.LV_SCN_015;
        }
        BridgeLvCode override = DELIVERY_OVERRIDES.get(errorCode);
        if (override != null) return override;
        BridgeLvCode own = CODES.get(errorCode);
        if (own != null) return own;
        return needsPerson ? BridgeLvCode.LV_SCN_016 : BridgeLvCode.LV_SCN_015;
    }

    /**
     * Same rule as {@code LexaVaultScannerDeliveryClient.retryableStatus}: a 5xx or 429 is retried, any other
     * status is LexaVault refusing. A test keeps the two equal.
     */
    public static boolean lexaVaultAnswerIsTransient(int status) {
        return status >= 500 || status == 429;
    }

    /** LexaVault's own LV code from its error body, kept only when it is code-shaped; otherwise null. */
    public static String safeLexaVaultLvCode(String candidate) {
        if (candidate == null) return null;
        String trimmed = candidate.trim();
        return LV_CODE.matcher(trimmed).matches() ? trimmed : null;
    }

    private static Integer statusOfLexaVaultHttpCode(String errorCode) {
        if (errorCode == null) return null;
        Matcher matcher = LEXAVAULT_HTTP.matcher(errorCode);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static void logGapOnce(String errorCode) {
        String shown = errorCode != null && SAFE_IDENTIFIER.matcher(errorCode).matches() ? errorCode : "<not code-shaped>";
        if (loggedGaps.size() >= MAX_LOGGED_GAPS || !loggedGaps.add(shown)) return;
        log.warn("SCAN_BRIDGE_LV_CODE_UNMAPPED bridgeCode={} code={}", shown, BridgeLvCode.LV_SYS_001.code());
    }
}
