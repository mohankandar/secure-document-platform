# LexaVault error codes (design)

**Status:** approved. Drafted by the LexaVault design chat, 2026-11-20, from the failure inventory of task 0022 (`docs/work/0022-failure-inventory.md`, 388 rows) after the fixes of task 0023.
**Owner's approval:** approved (owner, 2026-11-20). Additions of 2026-11-21 (LV-INT-035, LV-SCN-019 to 021, LV-REV-009, LV-CFG-011, LV-SYS-008, LV-SYS-009) approved by the owner, 2026-11-21.

## 1. Rules

- **Format:** `LV-<AREA>-<NNN>`. Within an area, numbers are grouped in tens by topic (for example intake by watched folder is 030–039). A code is **never reused or renumbered**; a retired code stays listed as retired.
- **Fixed already:** `LV-INT-005` (unknown sensitivity level, taken as Standard) and `LV-DLV-003` (destination refused the sensitivity level).
- **One code per cause a customer can recognise and act on,** not one per place in the code. Many inventory rows share one code. The exact internal reason (enum, exception type, HTTP status from a destination) is kept beside the code as the **detail**, for IT and the logs.
- **Every code has:** a **title** (the plain message people see), a **short description** (one or two sentences), a **kind**, **who acts**, and, for API refusals, the **HTTP status**. The **steps** ("what to do") go in the customer error code guide, not on screens or in Reports.
- **Never content:** no document text, redacted value, file or folder name, person's name, host with credentials or secret in a title, description or detail.
- **Safe for the user, exact for IT:** a person who is refused sees the title only (for example sign-in); the IT Admin, Support and the logs see the code, the detail and the correlation id.
- **Kinds:** **Refused** (at intake, nothing taken in), **Warning** (taken in, something to know), **Waiting** (not a failure yet), **Stopped** (taken in, processing stopped), **Export failed**, **Not allowed** (a person or client refused), **Set-up** (a setting is wrong), **System** (LexaVault itself or something it depends on).
- **Errors from other systems** keep their own code as the detail (for example Vero's `SDV-SEN-006` under `LV-DLV-003`).
- **API:** the error body gains `code` (`LV-…`) next to today's `errorCode`, which stays for API version 1 so existing source systems keep working.
- **Areas:** INT intake · SCN scanner and Scan Bridge · PRC processing · AI AI model · RED redaction · REV review and approval · DLV export · AUT sign-in and access · LIC licence · CFG settings · SYS system.

## 2. The codes

Inventory row numbers (`#n`) show where each code comes from.

### INT: intake (001–049)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-INT-001 | Request couldn't be read | The request body isn't valid JSON or a field has the wrong type. | Refused | Source system | 400 | 4, 292 |
| LV-INT-002 | Required header or part missing | A required header (Idempotency-Key, X-Content-SHA256) or the file part is missing or badly formed. | Refused | Source system | 400 | 7, 27, 54 |
| LV-INT-003 | Required field missing | A required field (package id, revision, document id, documents) is missing, blank or too long. | Refused | Source system | 400 | 29, 31, 53, 6, 2, 3 |
| LV-INT-004 | File too large | The file is larger than the limit for this source. | Refused | Source system | 400 | 5, 22, 67, 70 |
| LV-INT-005 | Unknown sensitivity level, taken as Standard | The level sent isn't one of Standard, Confidential, Highly Confidential, so the case or document was taken in as Standard. | Warning | Source system | 201 | 17, 19 |
| LV-INT-006 | File is empty | The file has no content. | Refused | Source system | 400 | 22 |
| LV-INT-007 | Not a PDF | The file isn't a PDF. Only PDFs are taken in. | Refused | Source system, person uploading | 400 | 23, 45, 60, 66, 70 |
| LV-INT-008 | PDF can't be opened | The PDF is damaged, or it is a password-protected file. Password-protected file: send it through the API. | Refused | Source system, IT Admin | 400 | 62, 0063 |
| LV-INT-009 | Too many pages | The PDF has more pages than the limit for this source. | Refused | Source system | 400 | 26 |
| LV-INT-010 | Too many documents | The case lists more documents than the limit for this source. | Refused | Source system | 400 | 30 |
| LV-INT-011 | Checksum doesn't match the manifest | The checksum sent with the file differs from the one in the case's manifest. | Refused | Source system | 409 | 24 |
| LV-INT-012 | File changed on the way | The file received doesn't match its checksum; it was damaged in transfer. Send it again. | Refused | Source system | 409 | 25 |
| LV-INT-013 | Required case field missing | A required or Primary case field (for example the loan number) is missing. | Refused | Source system | 400 | 12 |
| LV-INT-014 | Unknown case field | A case field was sent that isn't set up for this entity. | Refused | Source system, Business Admin | 400 | 11 |
| LV-INT-015 | Case field value not valid | A case field value breaks its rule (length, pattern, allowed values). | Refused | Source system, person | 400 | 46, 96 |
| LV-INT-016 | Case fields conflict with an existing case | The case fields match an existing case but disagree with it. | Refused | Source system, Business Admin | 400 | 10 |
| LV-INT-017 | Already received | The same content was already taken in; nothing new was created. | Refused | Nobody | 400 | 41, 42 |
| LV-INT-018 | Same revision sent with different content | This package and revision were sent before with a different manifest or fields. Send a new revision. | Refused | Source system | 409 | 15 |
| LV-INT-019 | Document id reused with different content | This document was already received with different content. | Refused | Source system | 409 | 20 |
| LV-INT-020 | Document not in the manifest | The document isn't listed in the case's manifest, or is listed twice. | Refused | Source system | 400 | 49, 50 |
| LV-INT-021 | Idempotency key already used | The key was already used for another document. | Refused | Source system | 409 | 51 |
| LV-INT-022 | Intake already complete | All documents for this case were already received. | Refused | Source system | 409 | 21, 52 |
| LV-INT-023 | Intake expired | Not all documents arrived before the deadline. Send the case again with retry. | Refused | Source system | 410 | 13, 28, 33, 18 |
| LV-INT-024 | Nothing to retry | A retry was asked for, but there is no earlier attempt. | Refused | Source system | 409 | 16 |
| LV-INT-025 | Case not found | No case with this id exists in this entity. | Refused | Source system, person | 404 | 14, 44 |
| LV-INT-026 | Case can't take documents now | The case was already submitted, or has no documents to submit. | Refused | Person | 409 | 43, 47, 48, 1 |
| LV-INT-027 | Busy, try again later | LexaVault is full for now; nothing was lost. Send again after the time given. | Refused | Source system | 429 | — |
| LV-INT-028 | Too many pages in one file | A PDF in a case can have up to 500 pages. Nothing was taken in from this file. | Refused | Person, source system | 400 | — (task 0038) |
| LV-INT-029 | Too many files in one case | A case can hold up to 50 files. Nothing more was taken in. | Refused | Person, source system | 400 | — (task 0038) |
| LV-INT-030 | Waiting for the trigger file | The folder drop has no trigger file yet, so it isn't taken in. | Waiting | Source system | — | 37 |
| LV-INT-031 | Trigger file can't be read | The trigger file isn't in the expected format, or names a file outside the folder. | Refused | Source system, IT Admin | — | 61 |
| LV-INT-032 | File named in the trigger file is missing | The trigger file lists a document that isn't in the folder. | Refused | Source system | — | 58 |
| LV-INT-033 | Folder drop has no documents | The drop has a trigger file but no PDFs. | Refused | Source system | — | 59 |
| LV-INT-034 | Watched folder can't be read or written | LexaVault can't list, read or move files in the watched folder. | System | IT Admin | — | 36, 39, 56, 57 |
| LV-INT-035 | Folder drop no longer matches its case | A resumed folder drop no longer matches the case it started. | Refused | Source system | — | 0025 (RECOVERY_CONFLICT) |
| LV-INT-036 | Password not accepted | The password sent with the PDF doesn't open it. Nothing was taken in. | Refused | Source system | 400 | 0063 |
| LV-INT-037 | Password sent the wrong way | A PDF password is taken only in the request body, over HTTPS. Nothing was taken in. | Refused | Source system | 400 | 0063 |
| LV-INT-040 | Source system can't be reached | Polling couldn't connect to the source, or it timed out. | Refused | IT Admin, source system | — | 69 |
| LV-INT-041 | Source system answered with an error | The source answered polling with an error status. | Refused | Source system | — | 65, 68 |
| LV-INT-042 | Source system's answer not understood | The source's list of cases or a document wasn't in the expected form. | Refused | Source system | — | 64, 66 |
| LV-INT-043 | Couldn't confirm receipt to the source | The case was taken in, but telling the source failed. It may send the case again. | Warning | IT Admin, source system | — | 63 |
| LV-INT-044 | Polling already running | A polling run for this source is already in progress. | Refused | Nobody | 409 | 9 |

### SCN: scanner and Scan Bridge (001–029)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-SCN-001 | Scan must be one document | A scan sends exactly one PDF per case. | Refused | IT Admin | 400 | 73 |
| LV-SCN-002 | Scanned file size doesn't match | The PDF received isn't the size the scanner announced. | Refused | IT Admin | 409 | 72, 74 |
| LV-SCN-003 | Scanner details missing | Required scanner details (site, scanner, capture mode) are missing or not valid. | Refused | IT Admin | 400 | 75 |
| LV-SCN-004 | Empty scan or not a PDF | The scan produced no pages, or the file isn't a PDF. | Refused | Operator | 400 | 76, 86 |
| LV-SCN-005 | Scan too large or disk full | The scan is over the size limit, or the workstation is low on disk space. | Refused | Operator, IT Admin | 503 | 88 |
| LV-SCN-006 | Scanner busy | A scan is already waiting, or the scan inbox isn't empty. | Refused | Operator | 409 | 84, 97 |
| LV-SCN-007 | Scan Bridge switched off or in the wrong mode | The bridge is disabled, or set to the simulator instead of the scanner (or the reverse). | Set-up | IT Admin | 503 | 85, 97 |
| LV-SCN-008 | Scanner not found | No supported scanner is connected or set up on this workstation. | Set-up | IT Admin | 503 | 99 |
| LV-SCN-009 | Scan didn't finish | The scan timed out, was cancelled, hit the page limit, or produced no pages. | Stopped | Operator | 504 | 98 |
| LV-SCN-010 | Scan session not found or not ready | The scan session ended, or the scan isn't ready to send. | Refused | Operator | 409 | 93, 94, 95 |
| LV-SCN-011 | Scan Bridge not running | The browser can't reach the Scan Bridge on this workstation. | System | Operator, IT Admin | — | 382 |
| LV-SCN-012 | Scan Bridge refused the request | The request came from a page or session the bridge doesn't trust. Reload the page. | Not allowed | IT Admin | 403 | 79, 80, 275 |
| LV-SCN-013 | Scan Bridge can't save its state | The bridge can't read or write its working files. | System | IT Admin | 503 | 77, 90, 377 |
| LV-SCN-014 | Scan Bridge can't start | The bridge stopped at start (address, another bridge running, missing settings). | Set-up | IT Admin | — | 81, 314, 378 |
| LV-SCN-015 | LexaVault can't be reached from the scanner | The bridge can't send the scan to LexaVault. | System | IT Admin | — | 82, 276 |
| LV-SCN-016 | LexaVault refused the scan | LexaVault refused the scan; its own code says why. | Refused | IT Admin | — | 83 |
| LV-SCN-017 | Scan waiting too long for LexaVault | LexaVault hasn't accepted the scan within the time limit. | Stopped | IT Admin | — | 0024 |
| LV-SCN-018 | Scan couldn't be sent | Sending failed after 3 tries. | Stopped | IT Admin | — | 0023 |
| LV-SCN-019 | Scan can't be cancelled now | The scan is already being sent, or needs attention first. | Refused | Operator | 409 | 92 |
| LV-SCN-020 | Scan resumed after a restart | The Scan Bridge restarted and picked up the scan where it left off. | Warning | Nobody | — | 0025 (RECOVERED_AFTER_RESTART) |
| LV-SCN-021 | Waiting for LexaVault to accept the scan | The scan was sent; LexaVault hasn't confirmed it yet. | Waiting | Nobody | — | 0024 (acceptance pending) |

### PRC: processing (001–019)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-PRC-001 | PDF can't be opened | The PDF is damaged or protected with a password. | Stopped | Source system | — | 113 |
| LV-PRC-002 | No readable text | No text could be read from the document. | Stopped | Source system | — | 102, 105 |
| LV-PRC-003 | Page too poor to read | A page's scan quality is too low to read reliably. | Stopped | Source system | — | 106 |
| LV-PRC-004 | Text reading timed out | Reading text from a page took too long. | Stopped | IT Admin | — | 109 |
| LV-PRC-005 | Text reader not available | The text reader (OCR) is missing or not set up. | System | IT Admin | — | 110, 111, 112, 371 |
| LV-PRC-006 | Page couldn't be prepared | A page couldn't be turned into an image for reading. | Stopped | IT Admin | — | 104, 107 |
| LV-PRC-007 | Original document missing | The original file isn't in storage. | System | IT Admin | — | 355, 363 |
| LV-PRC-008 | Processing stopped after repeated failures | Processing failed 3 times, so it stopped. | Stopped | IT Admin | — | 346, 347 |
| LV-PRC-009 | Document already being processed | Another process is working on this document. | Stopped | Nobody | — | 360 |
| LV-PRC-010 | Form type can't be read | The document is a form made for one PDF viewer (XFA), which LexaVault can't read. It was not processed. | Stopped | Processor | — | task 0053 |

### AI: AI model (001–019)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-AI-001 | AI model not available | The AI model couldn't be reached on any server. | System | IT Admin | — | 114, 116, 120, 122, 130, 322, 323 |
| LV-AI-002 | AI model timed out | The AI model took too long to answer. | System | IT Admin | — | 127 |
| LV-AI-003 | AI model busy | The AI model asked LexaVault to slow down. | System | IT Admin | — | 121 |
| LV-AI-004 | AI model refused the request | The model isn't installed, or the key or settings are wrong. | Set-up | IT Admin | — | 117, 123, 128 |
| LV-AI-005 | AI answer couldn't be used | The model's answer was empty, cut short or not in the expected form. | System | IT Admin | — | 124, 125, 126, 129, 132 |
| LV-AI-006 | AI model not set up for this entity | This entity has no AI settings. | Set-up | Platform Ops | — | 135 |
| LV-AI-007 | AI model couldn't be loaded | Loading the AI model at start failed. | Set-up | IT Admin | — | 133, 329 |
| LV-AI-008 | AI model isn't the approved one | The installed AI model isn't the version approved for LexaVault, so it wasn't used. | Set-up | IT Admin | — | task 0064 |

### RED: redaction (001–009)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-RED-001 | A finding has no location | A confirmed finding has no box on the page, so it can't be blacked out. | Stopped | Reviewer | — | 137 |
| LV-RED-002 | Redacted copy failed the safety check | The redacted copy didn't pass verification (for example readable text left under a box). It was not exported. | Stopped | IT Admin | — | 138, 139, 140, 142 |
| LV-RED-003 | Redaction couldn't be applied | The PDF engine couldn't apply the redactions. | Stopped | IT Admin | — | 141 |
| LV-RED-004 | Redacted copy couldn't be stored | Storing the redacted copy failed. | System | IT Admin | — | 364 |
| LV-RED-005 | A redacted value is in the output text | The final check found a value that was redacted in the text of the redacted copy (for example in the searchable text layer). It was not exported. | Stopped | IT Admin | — | task 0044 |

### REV: review and approval (001–019)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-REV-001 | Someone else already decided this | Another person decided this finding or case first. | Refused | Nobody | 409 | 160, 167, 174 |
| LV-REV-002 | Case is no longer in review | The case moved on (approved, resumed, stopped) before this action. | Refused | Nobody | 409 | 147, 154, 158, 162, 163, 169, 170, 172 |
| LV-REV-003 | Findings still open | Decide every finding before continuing. | Refused | Reviewer | 400 | 136, 153, 164 |
| LV-REV-004 | Confirmation needed | Tick the confirmation before you submit. | Refused | Reviewer | 400 | 150 |
| LV-REV-005 | Only the assigned reviewer can do this | This review is assigned to someone else. | Not allowed | Reviewer | 403 | 155, 171, 173 |
| LV-REV-006 | Box not valid | The box is outside the page or has no size. | Refused | Reviewer | 400 | 143, 148, 159, 165, 168 |
| LV-REV-007 | Can't process again | The original is no longer kept, or the case reached its limit of runs. | Refused | Nobody | 400 | 146, 199 |
| LV-REV-008 | Can't continue by hand | Continuing manually is only for cases stopped at the AI step. | Refused | Reviewer | 400 | 151 |
| LV-REV-009 | Page doesn't exist | The page asked for isn't in the document. | Refused | Nobody | 400 | 0025 (review page out of range) |
| LV-REV-010 | Rejected: wrong document | A person rejected the case: it holds the wrong document. | Stopped | Source system | — | 0071 |
| LV-REV-011 | Rejected: unreadable | A person rejected the case: its document is unreadable. | Stopped | Source system | — | 0071 |
| LV-REV-012 | Rejected: out of scope | A person rejected the case: it is out of scope. | Stopped | Source system | — | 0071 |
| LV-REV-013 | Rejected: duplicate | A person rejected the case: it is a duplicate. | Stopped | Source system | — | 0071 |
| LV-REV-014 | Rejected: other (see ticket) | A person rejected the case for another reason, written in a ticket. | Stopped | Source system | — | 0071 |

### DLV: export (001–029)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-DLV-001 | Destination can't be reached | LexaVault couldn't connect to the destination. | Export failed | IT Admin | — | 215, 226, 222 |
| LV-DLV-002 | Destination busy or down | The destination answered "busy" or with a server error. | Export failed | IT Admin | — | 205, 218, 225 |
| LV-DLV-003 | Destination refused the sensitivity level | The destination doesn't accept the case's or a document's sensitivity level. | Export failed | IT Admin | — | 211, 216 |
| LV-DLV-004 | Destination refused the file | The destination refused the case or a document. Its own code is in the details. | Export failed | IT Admin | — | 204, 217, 224 |
| LV-DLV-005 | Sign-in to the destination failed | The destination refused LexaVault's credentials, or the credential can't be found. | Set-up | IT Admin | — | 203, 214, (401/403 from 204, 224) |
| LV-DLV-006 | Destination not set up or switched off | There is no enabled destination, or its address isn't valid. | Set-up | IT Admin | — | 186, 188, 208, 213, 227 |
| LV-DLV-007 | Destination's answer not understood | The destination's answer was missing information or not in the expected form. | Export failed | IT Admin | — | 206, 210, 212, 219, 220, 223 |
| LV-DLV-008 | Not every document was confirmed | The destination didn't confirm all documents of the case. | Export failed | IT Admin | — | 195, 207, 209 |
| LV-DLV-009 | Already received by the destination | The destination already has this case. Counted as exported. *(Reserved for the export redesign.)* | Warning | Nobody | — | — |
| LV-DLV-010 | Export folder already exists | A folder for this case run is already in the export location. | Export failed | IT Admin | — | 201 |
| LV-DLV-011 | Nothing ready to export | A document isn't finished, or its redacted copy is missing. | Export failed | IT Admin | — | 178, 190, 197, 180, 196, 198 |
| LV-DLV-012 | Split rule matched more than once | A page matched more than one split rule. | Set-up | Business Admin | — | 187 |
| LV-DLV-013 | Split document couldn't be made | Finding the split pages or building the split PDF failed. | Export failed | IT Admin | — | 189, 191, 181 |
| LV-DLV-014 | Can't retry this failure | This failure needs a fix first; retrying won't help. | Refused | IT Admin | 400 | 177, 194 |
| LV-DLV-015 | Resend failed | A person's resend failed, so the case went to the IT error queue. *(Reserved for the export redesign.)* | Export failed | IT Admin | — | — |

### AUT: sign-in and access (001–029)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-AUT-001 | Please sign in again | The session ended or the sign-in can't be checked. | Not allowed | Person | 401 | 252, 278 |
| LV-AUT-002 | No access to LexaVault | You're signed in, but not given a LexaVault role. | Not allowed | IT Admin | 403 | 245, 265, 266, 271, 272, 284 |
| LV-AUT-003 | No access to this entity | You can't open this entity, or it was removed from your access. | Not allowed | IT Admin | 403 | 253, 269, 280, 283 |
| LV-AUT-004 | Choose an entity | You have access to more than one entity; choose one. | Refused | Person | 403 | 268 |
| LV-AUT-005 | Entity not active | This entity is switched off or not set up yet. | Not allowed | Platform Ops | 403 | 236, 264 |
| LV-AUT-006 | Not allowed | Your role doesn't allow this action. | Not allowed | Business Admin, IT Admin | 403 | 144, 234, 235, 238, 243, 244, 251, 281 |
| LV-AUT-007 | Sign-in isn't working right now | The login system's settings don't match LexaVault's, or it can't be reached. The IT Admin has the details. | Set-up | IT Admin | 403 | 229, 232, 233, 247, 248, 249, 250, 267, 273, 274, 230, 231, 246, 237 |
| LV-AUT-008 | Integration client not allowed | The source system's client isn't linked to an enabled connection for this entity, or is using another connection's case. | Not allowed | IT Admin | 403 | 254, 255, 256, 257, 258, 259, 260, 270 |
| LV-AUT-009 | Sign-in didn't complete | The browser's sign-in with the login system failed part way. | Not allowed | Person, IT Admin | — | 277 |
| LV-AUT-010 | Feature not switched on | This feature isn't switched on for your entity. | Set-up | Platform Ops | 403 | 262, 309, 279 |

### LIC: licence (001–009)

*Licensing is a shared capability; these are LexaVault's codes for showing its refusals.*

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-LIC-001 | No licence installed | No licence is stored for this installation. | Set-up | IT Admin | 403 | 261, 308, 358, 287 |
| LV-LIC-002 | Licence expired | The licence has expired, its grace period is over, or it hasn't started yet. No new cases are taken in; cases already in LexaVault are finished. | Set-up | IT Admin | 403 | 261, 308, 358, 287 |
| LV-LIC-003 | Licence not valid | The licence can't be read or trusted, or it doesn't cover this entity. | Set-up | IT Admin | 403 | 261, 287, 325 |
| LV-LIC-004 | Licence doesn't include this | The licence doesn't cover LexaVault. | Set-up | IT Admin | 403 | 261, 308 |

### CFG: settings (001–019)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-CFG-001 | Setting not valid | A value breaks its rule; the message names the field and the allowed range. | Refused | Admin who saved it | 400 | 285, 286, 288, 289, 290, 293, 294, 295, 300, 310 |
| LV-CFG-002 | Case fields not set up | The entity needs active case fields with exactly one Primary. | Set-up | Business Admin | 400 | 301 |
| LV-CFG-003 | Connection settings not valid | A connection's settings are missing, duplicated or not allowed (for example a private address). | Refused | IT Admin | 400 | 298, 305, 306, 311, 312 |
| LV-CFG-004 | Connection test failed | Testing the connection failed; the details say at which step. | Set-up | IT Admin | — | 35, 179, 304, 307, 319 |
| LV-CFG-005 | More than one main export destination | Only one enabled main export destination is allowed per entity. | Set-up | IT Admin | 409 | 313 |
| LV-CFG-006 | Split rule not valid | A split rule is incomplete, duplicated or points to a missing destination. | Refused | IT Admin | 400 | 299 |
| LV-CFG-007 | Watched folder not set up for this entity | The watched folder belongs to another entity or isn't set up. | Set-up | IT Admin | 403 | 296, 297, 340 |
| LV-CFG-008 | Login system settings not valid | The draft login settings are incomplete or failed their test. | Set-up | IT Admin | 400 | 240, 241 |
| LV-CFG-009 | Scanner settings not valid | A scanner setting is out of range. | Set-up | IT Admin | 500 | 315 |
| LV-CFG-010 | Connection switched off | The connection is disabled or not of the right kind. | Set-up | IT Admin | 403 | 302, 303 |
| LV-CFG-011 | Support action not possible now | The case or incident isn't in a state that allows this action (for example the incident is closed). | Refused | IT Admin | 409 | 0025 (hold extension, restart, closed incident, Dashboard removal) |

### SYS: system (001–019)

| Code | Title | Short description | Kind | Who acts | HTTP | Rows |
|---|---|---|---|---|---|---|
| LV-SYS-001 | Something went wrong | An unexpected error. Give IT the reference number shown. | System | IT Admin | 500 | 330, 331, 332, 354, 365, 369, 383, 384 |
| LV-SYS-002 | Storage not available | LexaVault can't read or write its document storage. | System | IT Admin | 503 | 343, 366, 374, 375 |
| LV-SYS-003 | Database not available | LexaVault can't reach its database. | System | IT Admin | 503 | 335, 337, 341, 376 |
| LV-SYS-004 | Too much work queued | LexaVault couldn't take more work just now. | System | IT Admin | 503 | 344 |
| LV-SYS-005 | Start-up setting wrong | LexaVault stopped at start because a setting is wrong; the log names it. | Set-up | IT Admin | — | 324, 326, 328, 348, 349, 350, 351, 379, 327 |
| LV-SYS-006 | Health check failed | A part LexaVault needs (AI model, text reader, database, storage, destination) failed its check. | System | IT Admin | — | 336, 337, 338, 352, 353, 380 |
| LV-SYS-007 | Temporary file left behind | A temporary file couldn't be deleted after a failure; IT should remove it. | System | IT Admin | — | 91, 356, 370, 372, 373 |
| LV-SYS-008 | Item not found | The item asked for (a rule, a connection, a file) doesn't exist in this entity. | Refused | Person, IT Admin | 404 | 0025 (not-found other than a case) |
| LV-SYS-009 | Unknown request | The address or method isn't one LexaVault offers. | Refused | Source system | 404 / 405 | 0025 (unknown endpoint, wrong method) |

**Totals:** INT 42 · SCN 21 · PRC 10 · AI 8 · RED 5 · REV 9 · DLV 15 · AUT 10 · LIC 4 · CFG 11 · SYS 9 = **144 codes** (2 reserved: LV-DLV-009, LV-DLV-015; LV-INT-027 is in use since task 0067). *(The first version said 124; the tables then held 129. Eight codes were added on 2026-11-21 after task 0025; LV-INT-028 and LV-INT-029 on 2026-11-22 by task 0038, for the case limits; LV-RED-005 by task 0044; LV-PRC-010 by task 0053; LV-INT-036 and 037 by task 0063; LV-AI-008 by task 0064.)*

## 3. Rows with no code on purpose

- Silent internal steps that are now logged with a code by task 0023 but never reach a customer (for example #108, #131, #134, #361, #362, #367): they log the nearest code or `LV-SYS-001`.
- Dead code (#387, #388) and intended non-refusals (#145 four-eyes, backlog C1; #166 no reject endpoint; #176 already delivered).
- Front-end load failures (#228, #282, #316, #381, #385) show the server's code if there is one, else `LV-SYS-001`.

## 4. Where the codes show

- **API error body:** `code` and `message` (the title), plus today's `errorCode`, `correlationId`, `timestamp`.
- **Screens:** Support list and Problem page, the case panel, toasts and admin screens show the title and the code; the detail stays under Technical details.
- **Audit trail:** every refusal, stop and export failure records `code=LV-…` (and the detail); never content.
- **Watched-folder observations, polling runs, Scan Bridge:** the code.
- **Reports:** "Needs attention, by reason" counts by code.
- **The customer error code guide** (FAQ page or PDF): per code, the title, meaning, who acts and the steps. Built from the same list, so it can't drift.
