package com.securedoc.platform.domain.error;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0025: the one list of LexaVault error codes ({@code LV-<AREA>-<NNN>}). Each code has the title people see,
 * a short description, a kind, who acts and, for API refusals, the HTTP status.
 *
 * <p>The source of truth is {@code docs/work/lv-error-codes.md}; {@code LvErrorCodeRegistryTest} fails if this list
 * and that document differ (same codes, titles, descriptions, kinds, who acts, HTTP status, reserved). A code is
 * never renumbered or reused. Titles and descriptions never hold content: no document text, redacted value, file or
 * folder name, person's name, host or secret.
 *
 * <p>The internal reason (an enum value, an {@code errorCode}, an exception type, a destination's own code) is kept
 * beside the code as the <em>detail</em>; the mapping from those to a code lives in one place in the application
 * module ({@code LvErrorCodes}).
 */
public enum LvErrorCode {

    INT_001("LV-INT-001", Area.INT, "Request couldn't be read",
            "The request body isn't valid JSON or a field has the wrong type.",
            Kind.REFUSED, "Source system", 400, false),
    INT_002("LV-INT-002", Area.INT, "Required header or part missing",
            "A required header (Idempotency-Key, X-Content-SHA256) or the file part is missing or badly formed.",
            Kind.REFUSED, "Source system", 400, false),
    INT_003("LV-INT-003", Area.INT, "Required field missing",
            "A required field (package id, revision, document id, documents) is missing, blank or too long.",
            Kind.REFUSED, "Source system", 400, false),
    INT_004("LV-INT-004", Area.INT, "File too large",
            "The file is larger than the limit for this source.",
            Kind.REFUSED, "Source system", 400, false),
    INT_005("LV-INT-005", Area.INT, "Unknown sensitivity level, taken as Standard",
            "The level sent isn't one of Standard, Confidential, Highly Confidential, so the case or document was taken in as Standard.",
            Kind.WARNING, "Source system", 201, false),
    INT_006("LV-INT-006", Area.INT, "File is empty",
            "The file has no content.",
            Kind.REFUSED, "Source system", 400, false),
    INT_007("LV-INT-007", Area.INT, "Not a PDF",
            "The file isn't a PDF. Only PDFs are taken in.",
            Kind.REFUSED, "Source system, person uploading", 400, false),
    INT_008("LV-INT-008", Area.INT, "PDF can't be opened",
            "The PDF is damaged or protected with a password.",
            Kind.REFUSED, "Source system", 400, false),
    INT_009("LV-INT-009", Area.INT, "Too many pages",
            "The PDF has more pages than the limit for this source.",
            Kind.REFUSED, "Source system", 400, false),
    INT_010("LV-INT-010", Area.INT, "Too many documents",
            "The case lists more documents than the limit for this source.",
            Kind.REFUSED, "Source system", 400, false),
    INT_011("LV-INT-011", Area.INT, "Checksum doesn't match the manifest",
            "The checksum sent with the file differs from the one in the case's manifest.",
            Kind.REFUSED, "Source system", 409, false),
    INT_012("LV-INT-012", Area.INT, "File changed on the way",
            "The file received doesn't match its checksum; it was damaged in transfer. Send it again.",
            Kind.REFUSED, "Source system", 409, false),
    INT_013("LV-INT-013", Area.INT, "Required case field missing",
            "A required or Primary case field (for example the loan number) is missing.",
            Kind.REFUSED, "Source system", 400, false),
    INT_014("LV-INT-014", Area.INT, "Unknown case field",
            "A case field was sent that isn't set up for this entity.",
            Kind.REFUSED, "Source system, Business Admin", 400, false),
    INT_015("LV-INT-015", Area.INT, "Case field value not valid",
            "A case field value breaks its rule (length, pattern, allowed values).",
            Kind.REFUSED, "Source system, person", 400, false),
    INT_016("LV-INT-016", Area.INT, "Case fields conflict with an existing case",
            "The case fields match an existing case but disagree with it.",
            Kind.REFUSED, "Source system, Business Admin", 400, false),
    INT_017("LV-INT-017", Area.INT, "Already received",
            "The same content was already taken in; nothing new was created.",
            Kind.REFUSED, "Nobody", 400, false),
    INT_018("LV-INT-018", Area.INT, "Same revision sent with different content",
            "This package and revision were sent before with a different manifest or fields. Send a new revision.",
            Kind.REFUSED, "Source system", 409, false),
    INT_019("LV-INT-019", Area.INT, "Document id reused with different content",
            "This document was already received with different content.",
            Kind.REFUSED, "Source system", 409, false),
    INT_020("LV-INT-020", Area.INT, "Document not in the manifest",
            "The document isn't listed in the case's manifest, or is listed twice.",
            Kind.REFUSED, "Source system", 400, false),
    INT_021("LV-INT-021", Area.INT, "Idempotency key already used",
            "The key was already used for another document.",
            Kind.REFUSED, "Source system", 409, false),
    INT_022("LV-INT-022", Area.INT, "Intake already complete",
            "All documents for this case were already received.",
            Kind.REFUSED, "Source system", 409, false),
    INT_023("LV-INT-023", Area.INT, "Intake expired",
            "Not all documents arrived before the deadline. Send the case again with retry.",
            Kind.REFUSED, "Source system", 410, false),
    INT_024("LV-INT-024", Area.INT, "Nothing to retry",
            "A retry was asked for, but there is no earlier attempt.",
            Kind.REFUSED, "Source system", 409, false),
    INT_025("LV-INT-025", Area.INT, "Case not found",
            "No case with this id exists in this entity.",
            Kind.REFUSED, "Source system, person", 404, false),
    INT_026("LV-INT-026", Area.INT, "Case can't take documents now",
            "The case was already submitted, or has no documents to submit.",
            Kind.REFUSED, "Person", 409, false),
    INT_027("LV-INT-027", Area.INT, "Busy, try again later",
            "LexaVault is full for now; nothing was lost. Send again after the time given.",
            Kind.REFUSED, "Source system", 429, true),
    INT_030("LV-INT-030", Area.INT, "Waiting for the trigger file",
            "The folder drop has no trigger file yet, so it isn't taken in.",
            Kind.WAITING, "Source system", null, false),
    INT_031("LV-INT-031", Area.INT, "Trigger file can't be read",
            "The trigger file isn't in the expected format, or names a file outside the folder.",
            Kind.REFUSED, "Source system, IT Admin", null, false),
    INT_032("LV-INT-032", Area.INT, "File named in the trigger file is missing",
            "The trigger file lists a document that isn't in the folder.",
            Kind.REFUSED, "Source system", null, false),
    INT_033("LV-INT-033", Area.INT, "Folder drop has no documents",
            "The drop has a trigger file but no PDFs.",
            Kind.REFUSED, "Source system", null, false),
    INT_034("LV-INT-034", Area.INT, "Watched folder can't be read or written",
            "LexaVault can't list, read or move files in the watched folder.",
            Kind.SYSTEM, "IT Admin", null, false),
    INT_035("LV-INT-035", Area.INT, "Folder drop no longer matches its case",
            "A resumed folder drop no longer matches the case it started.",
            Kind.REFUSED, "Source system", null, false),
    INT_040("LV-INT-040", Area.INT, "Source system can't be reached",
            "Polling couldn't connect to the source, or it timed out.",
            Kind.REFUSED, "IT Admin, source system", null, false),
    INT_041("LV-INT-041", Area.INT, "Source system answered with an error",
            "The source answered polling with an error status.",
            Kind.REFUSED, "Source system", null, false),
    INT_042("LV-INT-042", Area.INT, "Source system's answer not understood",
            "The source's list of cases or a document wasn't in the expected form.",
            Kind.REFUSED, "Source system", null, false),
    INT_043("LV-INT-043", Area.INT, "Couldn't confirm receipt to the source",
            "The case was taken in, but telling the source failed. It may send the case again.",
            Kind.WARNING, "IT Admin, source system", null, false),
    INT_044("LV-INT-044", Area.INT, "Polling already running",
            "A polling run for this source is already in progress.",
            Kind.REFUSED, "Nobody", 409, false),
    SCN_001("LV-SCN-001", Area.SCN, "Scan must be one document",
            "A scan sends exactly one PDF per case.",
            Kind.REFUSED, "IT Admin", 400, false),
    SCN_002("LV-SCN-002", Area.SCN, "Scanned file size doesn't match",
            "The PDF received isn't the size the scanner announced.",
            Kind.REFUSED, "IT Admin", 409, false),
    SCN_003("LV-SCN-003", Area.SCN, "Scanner details missing",
            "Required scanner details (site, scanner, capture mode) are missing or not valid.",
            Kind.REFUSED, "IT Admin", 400, false),
    SCN_004("LV-SCN-004", Area.SCN, "Empty scan or not a PDF",
            "The scan produced no pages, or the file isn't a PDF.",
            Kind.REFUSED, "Operator", 400, false),
    SCN_005("LV-SCN-005", Area.SCN, "Scan too large or disk full",
            "The scan is over the size limit, or the workstation is low on disk space.",
            Kind.REFUSED, "Operator, IT Admin", 503, false),
    SCN_006("LV-SCN-006", Area.SCN, "Scanner busy",
            "A scan is already waiting, or the scan inbox isn't empty.",
            Kind.REFUSED, "Operator", 409, false),
    SCN_007("LV-SCN-007", Area.SCN, "Scan Bridge switched off or in the wrong mode",
            "The bridge is disabled, or set to the simulator instead of the scanner (or the reverse).",
            Kind.SET_UP, "IT Admin", 503, false),
    SCN_008("LV-SCN-008", Area.SCN, "Scanner not found",
            "No supported scanner is connected or set up on this workstation.",
            Kind.SET_UP, "IT Admin", 503, false),
    SCN_009("LV-SCN-009", Area.SCN, "Scan didn't finish",
            "The scan timed out, was cancelled, hit the page limit, or produced no pages.",
            Kind.STOPPED, "Operator", 504, false),
    SCN_010("LV-SCN-010", Area.SCN, "Scan session not found or not ready",
            "The scan session ended, or the scan isn't ready to send.",
            Kind.REFUSED, "Operator", 409, false),
    SCN_011("LV-SCN-011", Area.SCN, "Scan Bridge not running",
            "The browser can't reach the Scan Bridge on this workstation.",
            Kind.SYSTEM, "Operator, IT Admin", null, false),
    SCN_012("LV-SCN-012", Area.SCN, "Scan Bridge refused the request",
            "The request came from a page or session the bridge doesn't trust. Reload the page.",
            Kind.NOT_ALLOWED, "IT Admin", 403, false),
    SCN_013("LV-SCN-013", Area.SCN, "Scan Bridge can't save its state",
            "The bridge can't read or write its working files.",
            Kind.SYSTEM, "IT Admin", 503, false),
    SCN_014("LV-SCN-014", Area.SCN, "Scan Bridge can't start",
            "The bridge stopped at start (address, another bridge running, missing settings).",
            Kind.SET_UP, "IT Admin", null, false),
    SCN_015("LV-SCN-015", Area.SCN, "LexaVault can't be reached from the scanner",
            "The bridge can't send the scan to LexaVault.",
            Kind.SYSTEM, "IT Admin", null, false),
    SCN_016("LV-SCN-016", Area.SCN, "LexaVault refused the scan",
            "LexaVault refused the scan; its own code says why.",
            Kind.REFUSED, "IT Admin", null, false),
    SCN_017("LV-SCN-017", Area.SCN, "Scan waiting too long for LexaVault",
            "LexaVault hasn't accepted the scan within the time limit.",
            Kind.STOPPED, "IT Admin", null, false),
    SCN_018("LV-SCN-018", Area.SCN, "Scan couldn't be sent",
            "Sending failed after 3 tries.",
            Kind.STOPPED, "IT Admin", null, false),
    SCN_019("LV-SCN-019", Area.SCN, "Scan can't be cancelled now",
            "The scan is already being sent, or needs attention first.",
            Kind.REFUSED, "Operator", 409, false),
    SCN_020("LV-SCN-020", Area.SCN, "Scan resumed after a restart",
            "The Scan Bridge restarted and picked up the scan where it left off.",
            Kind.WARNING, "Nobody", null, false),
    SCN_021("LV-SCN-021", Area.SCN, "Waiting for LexaVault to accept the scan",
            "The scan was sent; LexaVault hasn't confirmed it yet.",
            Kind.WAITING, "Nobody", null, false),
    PRC_001("LV-PRC-001", Area.PRC, "PDF can't be opened",
            "The PDF is damaged or protected with a password.",
            Kind.STOPPED, "Source system", null, false),
    PRC_002("LV-PRC-002", Area.PRC, "No readable text",
            "No text could be read from the document.",
            Kind.STOPPED, "Source system", null, false),
    PRC_003("LV-PRC-003", Area.PRC, "Page too poor to read",
            "A page's scan quality is too low to read reliably.",
            Kind.STOPPED, "Source system", null, false),
    PRC_004("LV-PRC-004", Area.PRC, "Text reading timed out",
            "Reading text from a page took too long.",
            Kind.STOPPED, "IT Admin", null, false),
    PRC_005("LV-PRC-005", Area.PRC, "Text reader not available",
            "The text reader (OCR) is missing or not set up.",
            Kind.SYSTEM, "IT Admin", null, false),
    PRC_006("LV-PRC-006", Area.PRC, "Page couldn't be prepared",
            "A page couldn't be turned into an image for reading.",
            Kind.STOPPED, "IT Admin", null, false),
    PRC_007("LV-PRC-007", Area.PRC, "Original document missing",
            "The original file isn't in storage.",
            Kind.SYSTEM, "IT Admin", null, false),
    PRC_008("LV-PRC-008", Area.PRC, "Processing stopped after repeated failures",
            "Processing failed 3 times, so it stopped.",
            Kind.STOPPED, "IT Admin", null, false),
    PRC_009("LV-PRC-009", Area.PRC, "Document already being processed",
            "Another process is working on this document.",
            Kind.STOPPED, "Nobody", null, false),
    AI_001("LV-AI-001", Area.AI, "AI model not available",
            "The AI model couldn't be reached on any server.",
            Kind.SYSTEM, "IT Admin", null, false),
    AI_002("LV-AI-002", Area.AI, "AI model timed out",
            "The AI model took too long to answer.",
            Kind.SYSTEM, "IT Admin", null, false),
    AI_003("LV-AI-003", Area.AI, "AI model busy",
            "The AI model asked LexaVault to slow down.",
            Kind.SYSTEM, "IT Admin", null, false),
    AI_004("LV-AI-004", Area.AI, "AI model refused the request",
            "The model isn't installed, or the key or settings are wrong.",
            Kind.SET_UP, "IT Admin", null, false),
    AI_005("LV-AI-005", Area.AI, "AI answer couldn't be used",
            "The model's answer was empty, cut short or not in the expected form.",
            Kind.SYSTEM, "IT Admin", null, false),
    AI_006("LV-AI-006", Area.AI, "AI model not set up for this entity",
            "This entity has no AI settings.",
            Kind.SET_UP, "Platform Ops", null, false),
    AI_007("LV-AI-007", Area.AI, "AI model couldn't be loaded",
            "Loading the AI model at start failed.",
            Kind.SET_UP, "IT Admin", null, false),
    RED_001("LV-RED-001", Area.RED, "A finding has no location",
            "A confirmed finding has no box on the page, so it can't be blacked out.",
            Kind.STOPPED, "Reviewer", null, false),
    RED_002("LV-RED-002", Area.RED, "Redacted copy failed the safety check",
            "The redacted copy didn't pass verification (for example readable text left under a box). It was not exported.",
            Kind.STOPPED, "IT Admin", null, false),
    RED_003("LV-RED-003", Area.RED, "Redaction couldn't be applied",
            "The PDF engine couldn't apply the redactions.",
            Kind.STOPPED, "IT Admin", null, false),
    RED_004("LV-RED-004", Area.RED, "Redacted copy couldn't be stored",
            "Storing the redacted copy failed.",
            Kind.SYSTEM, "IT Admin", null, false),
    REV_001("LV-REV-001", Area.REV, "Someone else already decided this",
            "Another person decided this finding or case first.",
            Kind.REFUSED, "Nobody", 409, false),
    REV_002("LV-REV-002", Area.REV, "Case is no longer in review",
            "The case moved on (approved, resumed, stopped) before this action.",
            Kind.REFUSED, "Nobody", 409, false),
    REV_003("LV-REV-003", Area.REV, "Findings still open",
            "Decide every finding before continuing.",
            Kind.REFUSED, "Reviewer", 400, false),
    REV_004("LV-REV-004", Area.REV, "Confirmation needed",
            "Tick the confirmation before you submit.",
            Kind.REFUSED, "Reviewer", 400, false),
    REV_005("LV-REV-005", Area.REV, "Only the assigned reviewer can do this",
            "This review is assigned to someone else.",
            Kind.NOT_ALLOWED, "Reviewer", 403, false),
    REV_006("LV-REV-006", Area.REV, "Box not valid",
            "The box is outside the page or has no size.",
            Kind.REFUSED, "Reviewer", 400, false),
    REV_007("LV-REV-007", Area.REV, "Can't process again",
            "The original is no longer kept, or the case reached its limit of runs.",
            Kind.REFUSED, "Nobody", 400, false),
    REV_008("LV-REV-008", Area.REV, "Can't continue by hand",
            "Continuing manually is only for cases stopped at the AI step.",
            Kind.REFUSED, "Reviewer", 400, false),
    REV_009("LV-REV-009", Area.REV, "Page doesn't exist",
            "The page asked for isn't in the document.",
            Kind.REFUSED, "Nobody", 400, false),
    DLV_001("LV-DLV-001", Area.DLV, "Destination can't be reached",
            "LexaVault couldn't connect to the destination.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_002("LV-DLV-002", Area.DLV, "Destination busy or down",
            "The destination answered \"busy\" or with a server error.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_003("LV-DLV-003", Area.DLV, "Destination refused the sensitivity level",
            "The destination doesn't accept the case's or a document's sensitivity level.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_004("LV-DLV-004", Area.DLV, "Destination refused the file",
            "The destination refused the case or a document. Its own code is in the details.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_005("LV-DLV-005", Area.DLV, "Sign-in to the destination failed",
            "The destination refused LexaVault's credentials, or the credential can't be found.",
            Kind.SET_UP, "IT Admin", null, false),
    DLV_006("LV-DLV-006", Area.DLV, "Destination not set up or switched off",
            "There is no enabled destination, or its address isn't valid.",
            Kind.SET_UP, "IT Admin", null, false),
    DLV_007("LV-DLV-007", Area.DLV, "Destination's answer not understood",
            "The destination's answer was missing information or not in the expected form.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_008("LV-DLV-008", Area.DLV, "Not every document was confirmed",
            "The destination didn't confirm all documents of the case.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_009("LV-DLV-009", Area.DLV, "Already received by the destination",
            "The destination already has this case. Counted as exported.",
            Kind.WARNING, "Nobody", null, true),
    DLV_010("LV-DLV-010", Area.DLV, "Export folder already exists",
            "A folder for this case run is already in the export location.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_011("LV-DLV-011", Area.DLV, "Nothing ready to export",
            "A document isn't finished, or its redacted copy is missing.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_012("LV-DLV-012", Area.DLV, "Split rule matched more than once",
            "A page matched more than one split rule.",
            Kind.SET_UP, "Business Admin", null, false),
    DLV_013("LV-DLV-013", Area.DLV, "Split document couldn't be made",
            "Finding the split pages or building the split PDF failed.",
            Kind.EXPORT_FAILED, "IT Admin", null, false),
    DLV_014("LV-DLV-014", Area.DLV, "Can't retry this failure",
            "This failure needs a fix first; retrying won't help.",
            Kind.REFUSED, "IT Admin", 400, false),
    DLV_015("LV-DLV-015", Area.DLV, "Resend failed",
            "A person's resend failed, so the case went to the IT error queue.",
            Kind.EXPORT_FAILED, "IT Admin", null, true),
    AUT_001("LV-AUT-001", Area.AUT, "Please sign in again",
            "The session ended or the sign-in can't be checked.",
            Kind.NOT_ALLOWED, "Person", 401, false),
    AUT_002("LV-AUT-002", Area.AUT, "No access to LexaVault",
            "You're signed in, but not given a LexaVault role.",
            Kind.NOT_ALLOWED, "IT Admin", 403, false),
    AUT_003("LV-AUT-003", Area.AUT, "No access to this entity",
            "You can't open this entity, or it was removed from your access.",
            Kind.NOT_ALLOWED, "IT Admin", 403, false),
    AUT_004("LV-AUT-004", Area.AUT, "Choose an entity",
            "You have access to more than one entity; choose one.",
            Kind.REFUSED, "Person", 403, false),
    AUT_005("LV-AUT-005", Area.AUT, "Entity not active",
            "This entity is switched off or not set up yet.",
            Kind.NOT_ALLOWED, "Platform Ops", 403, false),
    AUT_006("LV-AUT-006", Area.AUT, "Not allowed",
            "Your role doesn't allow this action.",
            Kind.NOT_ALLOWED, "Business Admin, IT Admin", 403, false),
    AUT_007("LV-AUT-007", Area.AUT, "Sign-in isn't working right now",
            "The login system's settings don't match LexaVault's, or it can't be reached. The IT Admin has the details.",
            Kind.SET_UP, "IT Admin", 403, false),
    AUT_008("LV-AUT-008", Area.AUT, "Integration client not allowed",
            "The source system's client isn't linked to an enabled connection for this entity, or is using another connection's case.",
            Kind.NOT_ALLOWED, "IT Admin", 403, false),
    AUT_009("LV-AUT-009", Area.AUT, "Sign-in didn't complete",
            "The browser's sign-in with the login system failed part way.",
            Kind.NOT_ALLOWED, "Person, IT Admin", null, false),
    AUT_010("LV-AUT-010", Area.AUT, "Feature not switched on",
            "This feature isn't switched on for your entity.",
            Kind.SET_UP, "Platform Ops", 403, false),
    LIC_001("LV-LIC-001", Area.LIC, "No licence installed",
            "This entity has no LexaVault licence.",
            Kind.SET_UP, "Platform Ops", 403, false),
    LIC_002("LV-LIC-002", Area.LIC, "Licence expired",
            "The licence has expired or isn't valid yet.",
            Kind.SET_UP, "Platform Ops", 403, false),
    LIC_003("LV-LIC-003", Area.LIC, "Licence not valid",
            "The licence's signature or entity doesn't match.",
            Kind.SET_UP, "Platform Ops", 403, false),
    LIC_004("LV-LIC-004", Area.LIC, "Licence doesn't include this",
            "The licence doesn't cover this feature.",
            Kind.SET_UP, "Platform Ops", 403, false),
    CFG_001("LV-CFG-001", Area.CFG, "Setting not valid",
            "A value breaks its rule; the message names the field and the allowed range.",
            Kind.REFUSED, "Admin who saved it", 400, false),
    CFG_002("LV-CFG-002", Area.CFG, "Case fields not set up",
            "The entity needs active case fields with exactly one Primary.",
            Kind.SET_UP, "Business Admin", 400, false),
    CFG_003("LV-CFG-003", Area.CFG, "Connection settings not valid",
            "A connection's settings are missing, duplicated or not allowed (for example a private address).",
            Kind.REFUSED, "IT Admin", 400, false),
    CFG_004("LV-CFG-004", Area.CFG, "Connection test failed",
            "Testing the connection failed; the details say at which step.",
            Kind.SET_UP, "IT Admin", null, false),
    CFG_005("LV-CFG-005", Area.CFG, "More than one main export destination",
            "Only one enabled main export destination is allowed per entity.",
            Kind.SET_UP, "IT Admin", 409, false),
    CFG_006("LV-CFG-006", Area.CFG, "Split rule not valid",
            "A split rule is incomplete, duplicated or points to a missing destination.",
            Kind.REFUSED, "IT Admin", 400, false),
    CFG_007("LV-CFG-007", Area.CFG, "Watched folder not set up for this entity",
            "The watched folder belongs to another entity or isn't set up.",
            Kind.SET_UP, "IT Admin", 403, false),
    CFG_008("LV-CFG-008", Area.CFG, "Login system settings not valid",
            "The draft login settings are incomplete or failed their test.",
            Kind.SET_UP, "IT Admin", 400, false),
    CFG_009("LV-CFG-009", Area.CFG, "Scanner settings not valid",
            "A scanner setting is out of range.",
            Kind.SET_UP, "IT Admin", 500, false),
    CFG_010("LV-CFG-010", Area.CFG, "Connection switched off",
            "The connection is disabled or not of the right kind.",
            Kind.SET_UP, "IT Admin", 403, false),
    CFG_011("LV-CFG-011", Area.CFG, "Support action not possible now",
            "The case or incident isn't in a state that allows this action (for example the incident is closed).",
            Kind.REFUSED, "IT Admin", 409, false),
    SYS_001("LV-SYS-001", Area.SYS, "Something went wrong",
            "An unexpected error. Give IT the reference number shown.",
            Kind.SYSTEM, "IT Admin", 500, false),
    SYS_002("LV-SYS-002", Area.SYS, "Storage not available",
            "LexaVault can't read or write its document storage.",
            Kind.SYSTEM, "IT Admin", 503, false),
    SYS_003("LV-SYS-003", Area.SYS, "Database not available",
            "LexaVault can't reach its database.",
            Kind.SYSTEM, "IT Admin", 503, false),
    SYS_004("LV-SYS-004", Area.SYS, "Too much work queued",
            "LexaVault couldn't take more work just now.",
            Kind.SYSTEM, "IT Admin", 503, false),
    SYS_005("LV-SYS-005", Area.SYS, "Start-up setting wrong",
            "LexaVault stopped at start because a setting is wrong; the log names it.",
            Kind.SET_UP, "IT Admin", null, false),
    SYS_006("LV-SYS-006", Area.SYS, "Health check failed",
            "A part LexaVault needs (AI model, text reader, database, storage, destination) failed its check.",
            Kind.SYSTEM, "IT Admin", null, false),
    SYS_007("LV-SYS-007", Area.SYS, "Temporary file left behind",
            "A temporary file couldn't be deleted after a failure; IT should remove it.",
            Kind.SYSTEM, "IT Admin", null, false),
    SYS_008("LV-SYS-008", Area.SYS, "Item not found",
            "The item asked for (a rule, a connection, a file) doesn't exist in this entity.",
            Kind.REFUSED, "Person, IT Admin", 404, false),
    SYS_009("LV-SYS-009", Area.SYS, "Unknown request",
            "The address or method isn't one LexaVault offers.",
            Kind.REFUSED, "Source system", false, 404, 405);

    /** The area a code belongs to, in the order the customer guide lists them. */
    public enum Area { INT, SCN, PRC, AI, RED, REV, DLV, AUT, LIC, CFG, SYS }

    /** What kind of failure a code is. */
    public enum Kind {
        /** Refused at intake, or a request refused: nothing taken in or changed. */
        REFUSED,
        /** Taken in; something to know. */
        WARNING,
        /** Not a failure yet. */
        WAITING,
        /** Taken in, processing stopped. */
        STOPPED,
        EXPORT_FAILED,
        /** A person or client refused. */
        NOT_ALLOWED,
        /** A setting is wrong. */
        SET_UP,
        /** LexaVault itself or something it depends on. */
        SYSTEM
    }

    /** The code given when a failure has no code of its own (the gap is logged with the failure's type). */
    public static final LvErrorCode FALLBACK = SYS_001;

    private final String code;
    private final Area area;
    private final String title;
    private final String description;
    private final Kind kind;
    private final String whoActs;
    private final List<Integer> httpStatuses;
    private final boolean reserved;

    LvErrorCode(String code, Area area, String title, String description, Kind kind, String whoActs,
                Integer httpStatus, boolean reserved) {
        this(code, area, title, description, kind, whoActs,
                reserved, httpStatus == null ? new int[0] : new int[] {httpStatus});
    }

    /** Task 0026: a code answered with more than one status (LV-SYS-009: 404 unknown address, 405 wrong method). */
    LvErrorCode(String code, Area area, String title, String description, Kind kind, String whoActs,
                boolean reserved, int... httpStatuses) {
        this.code = code;
        this.area = area;
        this.title = title;
        this.description = description;
        this.kind = kind;
        this.whoActs = whoActs;
        this.httpStatuses = Arrays.stream(httpStatuses).boxed().toList();
        this.reserved = reserved;
    }

    /** For example {@code LV-DLV-002}. */
    public String code() { return code; }

    public Area area() { return area; }

    /** The plain message people see, for example "Destination busy or down". */
    public String title() { return title; }

    public String description() { return description; }

    public Kind kind() { return kind; }

    /** Who acts, as the design names them (for example "IT Admin" or "Source system, Business Admin"). */
    public String whoActs() { return whoActs; }

    /** The HTTP status of an API refusal with this code; empty when the code is not an API refusal. */
    public Optional<Integer> httpStatus() { return httpStatuses.stream().findFirst(); }

    /** Every HTTP status an API refusal with this code can have, in the design's order (usually one). */
    public List<Integer> httpStatuses() { return httpStatuses; }

    /** The HTTP status as the design and the guide write it: "400", "404 / 405", or "—". */
    public String httpStatusLabel() {
        return httpStatuses.isEmpty() ? "—"
                : String.join(" / ", httpStatuses.stream().map(String::valueOf).toList());
    }

    /** Reserved for later work: listed, never given to a failure yet. */
    public boolean reserved() { return reserved; }

    /** The title and the code, as screens show them: "Destination busy or down · LV-DLV-002". */
    public String titleWithCode() { return title + " · " + code; }

    /** The code for {@code LV-…}, if it is one. */
    public static Optional<LvErrorCode> fromCode(String code) {
        if (code == null) return Optional.empty();
        String wanted = code.trim();
        return Arrays.stream(values()).filter(c -> c.code.equals(wanted)).findFirst();
    }

    /** Every code in an area, in number order. */
    public static List<LvErrorCode> inArea(Area area) {
        Objects.requireNonNull(area, "area");
        return Arrays.stream(values()).filter(c -> c.area == area).toList();
    }
}
