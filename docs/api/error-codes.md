# LexaVault error codes: customer guide

**Reviewed by the design chat, 2026-11-21.**

Every refusal, stop or warning in LexaVault has a code like `LV-INT-001`: `LV`, then the area (for example `INT` for intake), then a number.
You see the code in the API error body as `code` (next to the older `errorCode`), and on screens as "Title · LV-XXX-NNN"; when you ask IT for help, give them the code and the reference number (correlation id) shown with it.

**How to read each entry.** The heading gives the code and the title people see. **Means** says what happened. **Kind** says what state things are in: Refused (nothing taken in), Warning (taken in, something to know), Waiting (not a failure yet), Stopped (taken in, processing stopped), Export failed, Not allowed (a person or client was refused), Set-up (a setting is wrong) or System (LexaVault or something it depends on). **Who acts** is the person or system that can fix it. **HTTP status** is the status an API call gets ("—" when the code is not an API answer). **What to do** lists the steps for the person who acts.

The codes are listed by area: INT intake, SCN scanner and Scan Bridge, PRC processing, AI AI model, RED redaction, REV review and approval, DLV export, AUT sign-in and access, LIC licence, CFG settings, SYS system.

## INT: intake

### LV-INT-001 Request couldn't be read

- **Means:** The request body isn't valid JSON or a field has the wrong type.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Check that the request body is valid JSON.
2. Check that each field has the type the API expects (text, number, list or object).
3. Fix the request and send it again. Nothing was taken in.

### LV-INT-002 Required header or part missing

- **Means:** A required header (Idempotency-Key, X-Content-SHA256) or the file part is missing or badly formed.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Send the Idempotency-Key and X-Content-SHA256 headers with every document upload.
2. Make sure X-Content-SHA256 is the SHA-256 of the file: 64 hexadecimal characters.
3. Send the PDF in the multipart file part, then send the upload again.

### LV-INT-003 Required field missing

- **Means:** A required field (package id, revision, document id, documents) is missing, blank or too long.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Read the message: it names the field.
2. Fill in the field, and keep it within the allowed length (255 characters for ids and names).
3. Make sure the case lists at least one document, then send it again.

### LV-INT-004 File too large

- **Means:** The file is larger than the limit for this source.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Check the file's size against the limit for this source. Your IT Admin can see it under Integrations.
2. Make the file smaller, or split it into several documents.
3. If documents of this size are normal for you, ask your IT Admin whether the limit can be raised.
4. Send the file again.

### LV-INT-005 Unknown sensitivity level, taken as Standard

- **Means:** The level sent isn't one of Standard, Confidential, Highly Confidential, so the case or document was taken in as Standard.
- **Kind:** Warning
- **Who acts:** Source system
- **HTTP status:** 201

**What to do:**

1. Nothing was lost: the case is being processed at Standard.
2. If it needs a higher level, tell the person responsible for the case before it is exported.
3. Change your system to send exactly one of Standard, Confidential or Highly Confidential, spelled and capitalised like that.

### LV-INT-006 File is empty

- **Means:** The file has no content.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Check that the file has content before you send it.
2. Create or export the file again from where it came from.
3. Send it again.

### LV-INT-007 Not a PDF

- **Means:** The file isn't a PDF. Only PDFs are taken in.
- **Kind:** Refused
- **Who acts:** Source system, person uploading
- **HTTP status:** 400

**What to do:**

1. Save or convert the document as a PDF.
2. If you upload in the browser, choose the PDF file, not another kind of file.
3. If the file already is a PDF, it may be damaged: save it again as a PDF.
4. Send or upload it again.

### LV-INT-008 PDF can't be opened

- **Means:** The PDF is damaged or protected with a password.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. If the PDF has a password, save a copy without it.
2. If it is damaged, create it again from the original document.
3. Check that it opens in a normal PDF reader, then send it again.

### LV-INT-009 Too many pages

- **Means:** The PDF has more pages than the limit for this source.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Check the page count against the limit for this source. Your IT Admin can see it under Integrations.
2. Split the PDF into smaller documents and list each one in the case.
3. If documents of this size are normal for you, ask your IT Admin whether the limit can be raised, then send again.

### LV-INT-010 Too many documents

- **Means:** The case lists more documents than the limit for this source.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Check the number of documents against the limit for this source. Your IT Admin can see it under Integrations.
2. Send fewer documents per case, for example by splitting the case into several cases.
3. If cases of this size are normal for you, ask your IT Admin whether the limit can be raised, then send again.

### LV-INT-011 Checksum doesn't match the manifest

- **Means:** The checksum sent with the file differs from the one in the case's manifest.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Work out the SHA-256 of the exact file you upload.
2. Make sure the same value is in the case's manifest and in the X-Content-SHA256 header.
3. If the file changed after you created the case, send the case again as a new revision.

### LV-INT-012 File changed on the way

- **Means:** The file received doesn't match its checksum; it was damaged in transfer. Send it again.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Send the same file again with the same headers.
2. Make sure the checksum is worked out over the exact bytes you send.
3. If it keeps happening, check the network between your system and LexaVault (for example a proxy that changes uploads).

### LV-INT-013 Required case field missing

- **Means:** A required or Primary case field (for example the loan number) is missing.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Read the message: it names the case field.
2. Send the case again with that field filled in.
3. If you don't know which case fields are required, ask your Business Admin (Case fields).

### LV-INT-014 Unknown case field

- **Means:** A case field was sent that isn't set up for this entity.
- **Kind:** Refused
- **Who acts:** Source system, Business Admin
- **HTTP status:** 400

**What to do:**

1. Compare the field's name with the case fields set up for this entity.
2. Source system: if the name is wrong, correct it or stop sending the field.
3. Business Admin: if the field should exist, add it under Case fields.
4. Send the case again.

### LV-INT-015 Case field value not valid

- **Means:** A case field value breaks its rule (length, pattern, allowed values).
- **Kind:** Refused
- **Who acts:** Source system, person
- **HTTP status:** 400

**What to do:**

1. Read the message: it names the field and the rule.
2. Correct the value so it fits the rule.
3. Send or save the case again.
4. If the rule itself is wrong, ask your Business Admin to change it under Case fields.

### LV-INT-016 Case fields conflict with an existing case

- **Means:** The case fields match an existing case but disagree with it.
- **Kind:** Refused
- **Who acts:** Source system, Business Admin
- **HTTP status:** 400

**What to do:**

1. Compare the case fields you sent with the existing case that has the same Primary field.
2. Source system: if your values are wrong, correct them and send again.
3. Business Admin: if the existing case's values are wrong, check them with the team that owns the case.

### LV-INT-017 Already received

- **Means:** The same content was already taken in; nothing new was created.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 400

**What to do:**

1. Nothing to do: the content is already in LexaVault.
2. Find the earlier case if you need to follow it.
3. If you meant to send new content, send it as a new revision or a new case.

### LV-INT-018 Same revision sent with different content

- **Means:** This package and revision were sent before with a different manifest or fields. Send a new revision.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. If the content changed, send it again with a new revision number.
2. If you meant to repeat the same request, send exactly the same manifest, fields and sensitivity level as before.

### LV-INT-019 Document id reused with different content

- **Means:** This document was already received with different content.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Give every different document its own document id.
2. To replace a document that was already received, send the case again as a new revision.
3. To repeat an upload, send exactly the same file as before.

### LV-INT-020 Document not in the manifest

- **Means:** The document isn't listed in the case's manifest, or is listed twice.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 400

**What to do:**

1. Upload only documents that the case's manifest lists.
2. Check that each document id appears only once in the manifest.
3. If the manifest was wrong, send the case again as a new revision with the right list.

### LV-INT-021 Idempotency key already used

- **Means:** The key was already used for another document.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Use a new, unique Idempotency-Key for each document.
2. Reuse a key only when you repeat the upload of the same document.
3. Send the upload again.

### LV-INT-022 Intake already complete

- **Means:** All documents for this case were already received.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Nothing more is needed for this case: it is already being processed.
2. Read the case's status through the API to follow it.
3. If you have new or changed documents, send them as a new revision.

### LV-INT-023 Intake expired

- **Means:** Not all documents arrived before the deadline. Send the case again with retry.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 410

**What to do:**

1. Send the case again with the same package id and revision, and with retry switched on.
2. Upload every document before the new deadline.
3. If the content changed, use a new revision instead.

### LV-INT-024 Nothing to retry

- **Means:** A retry was asked for, but there is no earlier attempt.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 409

**What to do:**

1. Check that the package id and revision are the same as in the earlier attempt.
2. If this is the first time you send the case, send it without retry.

### LV-INT-025 Case not found

- **Means:** No case with this id exists in this entity.
- **Kind:** Refused
- **Who acts:** Source system, person
- **HTTP status:** 404

**What to do:**

1. Check the case id.
2. Check that you are working in the right entity.
3. If the case should exist, give IT the reference number shown.

### LV-INT-026 Case can't take documents now

- **Means:** The case was already submitted, or has no documents to submit.
- **Kind:** Refused
- **Who acts:** Person
- **HTTP status:** 409

**What to do:**

1. If the case was already submitted, you don't need to do anything more: open it to follow its progress.
2. If it has no documents, add at least one PDF, then submit.
3. To add documents to a case that was already submitted, create a new case.

### LV-INT-027 Busy, try again later

- **Means:** LexaVault is full for now; nothing was lost. Send again after the time given.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 429

Reserved: not used yet.

**What to do:**

1. Wait for the time given in the answer (Retry-After).
2. Send the same request again, with the same Idempotency-Key. Nothing was lost.
3. If it happens often, tell your IT Admin.

### LV-INT-028 Too many pages in one file

- **Means:** A PDF in a case can have up to 500 pages. Nothing was taken in from this file.
- **Kind:** Refused
- **Who acts:** Person, source system
- **HTTP status:** 400

**What to do:**

1. Split the PDF into files of 500 pages or fewer.
2. Add the parts to the case as separate files. A case can hold up to 50 files.
3. A source system sends the parts as separate documents of the case. The source's own page limit may be lower; your IT Admin can see it under Integrations.

### LV-INT-029 Too many files in one case

- **Means:** A case can hold up to 50 files. Nothing more was taken in.
- **Kind:** Refused
- **Who acts:** Person, source system
- **HTTP status:** 400

**What to do:**

1. Send the case with 50 files or fewer.
2. If more files belong together, split them over several cases.
3. Files already in the case stay as they are; nothing in them was changed.

### LV-INT-030 Waiting for the trigger file

- **Means:** The folder drop has no trigger file yet, so it isn't taken in.
- **Kind:** Waiting
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Write the trigger file once all documents of the drop are in place.
2. LexaVault takes the drop in on its next check.
3. If a drop keeps waiting, check that the trigger file was written into the same drop.

### LV-INT-031 Trigger file can't be read

- **Means:** The trigger file isn't in the expected format, or names a file outside the folder.
- **Kind:** Refused
- **Who acts:** Source system, IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the trigger file is in the agreed format.
2. Check that it lists only documents inside the same drop.
3. Send the drop again as a new drop.
4. IT Admin: the refused drop was moved aside; check it there if the source can't find the cause.

### LV-INT-032 File named in the trigger file is missing

- **Means:** The trigger file lists a document that isn't in the folder.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Check that every document the trigger file names is in the drop, with the same name.
2. Write the trigger file last, after all documents are copied.
3. Send the drop again.

### LV-INT-033 Folder drop has no documents

- **Means:** The drop has a trigger file but no PDFs.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Copy the PDFs into the drop before you write the trigger file.
2. Send the drop again.

### LV-INT-034 Watched folder can't be read or written

- **Means:** LexaVault can't list, read or move files in the watched folder.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the watched folder can be reached from the LexaVault server.
2. Check that LexaVault's account can read, write and move files there, and that there is free space.
3. After the fix, LexaVault picks the drops up again on its next check.

### LV-INT-035 Folder drop no longer matches its case

- **Means:** A resumed folder drop no longer matches the case it started.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Check whether the files in the drop were changed, added or removed after LexaVault started taking it in.
2. Put the complete, unchanged drop in the watched folder again as a new drop, with its trigger file.
3. If it happens again, give IT the reference number from the watched-folder view.

### LV-INT-040 Source system can't be reached

- **Means:** Polling couldn't connect to the source, or it timed out.
- **Kind:** Refused
- **Who acts:** IT Admin, source system
- **HTTP status:** —

**What to do:**

1. Check that the source system is running.
2. Check that LexaVault can reach it (network, firewall) and that its address under Integrations is right.
3. Run the connection test. Polling tries again at the next interval.

### LV-INT-041 Source system answered with an error

- **Means:** The source answered polling with an error status.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Look at the status in the polling run history.
2. Check your system's own logs for that time.
3. Fix the cause. Polling tries again at the next interval.

### LV-INT-042 Source system's answer not understood

- **Means:** The source's list of cases or a document wasn't in the expected form.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Check that your list of cases follows the agreed polling format.
2. Check that each document is sent as a PDF.
3. Fix the answer. Polling tries again at the next interval.

### LV-INT-043 Couldn't confirm receipt to the source

- **Means:** The case was taken in, but telling the source failed. It may send the case again.
- **Kind:** Warning
- **Who acts:** IT Admin, source system
- **HTTP status:** —

**What to do:**

1. Nothing was lost: the case was taken in.
2. Check that the source's receipt address is reachable and accepts the confirmation.
3. LexaVault sends the confirmation again on the next run. If the source sends the case again, LexaVault recognises it.

### LV-INT-044 Polling already running

- **Means:** A polling run for this source is already in progress.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 409

**What to do:**

1. Wait for the current run to finish.
2. Look at the run history for its result.
3. Start a new run if you still need one.

## SCN: scanner and Scan Bridge

### LV-SCN-001 Scan must be one document

- **Means:** A scan sends exactly one PDF per case.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Check that the Scan Bridge sends one PDF per case.
2. Check that the Scan Bridge version matches your LexaVault version.
3. If it happens again, give support the reference number.

### LV-SCN-002 Scanned file size doesn't match

- **Means:** The PDF received isn't the size the scanner announced.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 409

**What to do:**

1. Ask the operator to scan the document again.
2. If it happens again, check the workstation's disk and its network connection to LexaVault.
3. If it still happens, give support the reference number.

### LV-SCN-003 Scanner details missing

- **Means:** Required scanner details (site, scanner, capture mode) are missing or not valid.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Check the Scan Bridge settings on the workstation: site, scanner and capture mode must be filled in.
2. Restart the Scan Bridge.
3. Ask the operator to scan again.

### LV-SCN-004 Empty scan or not a PDF

- **Means:** The scan produced no pages, or the file isn't a PDF.
- **Kind:** Refused
- **Who acts:** Operator
- **HTTP status:** 400

**What to do:**

1. Check that paper is loaded the right way and that the scanner shows no error.
2. Scan again.
3. If it happens again, tell your IT Admin.

### LV-SCN-005 Scan too large or disk full

- **Means:** The scan is over the size limit, or the workstation is low on disk space.
- **Kind:** Refused
- **Who acts:** Operator, IT Admin
- **HTTP status:** 503

**What to do:**

1. Operator: scan fewer pages at a time, as separate cases.
2. IT Admin: free disk space on the workstation.
3. Scan again.

### LV-SCN-006 Scanner busy

- **Means:** A scan is already waiting, or the scan inbox isn't empty.
- **Kind:** Refused
- **Who acts:** Operator
- **HTTP status:** 409

**What to do:**

1. Finish or cancel the scan that is already waiting.
2. Start the new scan.
3. If the scanner still shows as busy, tell your IT Admin.

### LV-SCN-007 Scan Bridge switched off or in the wrong mode

- **Means:** The bridge is disabled, or set to the simulator instead of the scanner (or the reverse).
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Check that the Scan Bridge is switched on.
2. Check its mode: scanner for real scanning, simulator only for testing.
3. Restart the Scan Bridge after changing the setting.

### LV-SCN-008 Scanner not found

- **Means:** No supported scanner is connected or set up on this workstation.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Check that the scanner is connected, switched on and visible to the workstation.
2. Check the scanner setting in the Scan Bridge.
3. Restart the Scan Bridge and try again.

### LV-SCN-009 Scan didn't finish

- **Means:** The scan timed out, was cancelled, hit the page limit, or produced no pages.
- **Kind:** Stopped
- **Who acts:** Operator
- **HTTP status:** 504

**What to do:**

1. Check the scanner for a paper jam or an error message.
2. For a large stack, scan it in smaller parts.
3. Scan again. If it keeps stopping, tell your IT Admin.

### LV-SCN-010 Scan session not found or not ready

- **Means:** The scan session ended, or the scan isn't ready to send.
- **Kind:** Refused
- **Who acts:** Operator
- **HTTP status:** 409

**What to do:**

1. Wait until the scan shows as ready before you send it.
2. If the session ended, reload the page and scan again.
3. If it happens again, tell your IT Admin.

### LV-SCN-011 Scan Bridge not running

- **Means:** The browser can't reach the Scan Bridge on this workstation.
- **Kind:** System
- **Who acts:** Operator, IT Admin
- **HTTP status:** —

**What to do:**

1. Operator: start the Scan Bridge, or restart the workstation.
2. Reload the page.
3. IT Admin: check that the Scan Bridge is installed and running, and that the browser doesn't block calls to it.

### LV-SCN-012 Scan Bridge refused the request

- **Means:** The request came from a page or session the bridge doesn't trust. Reload the page.
- **Kind:** Not allowed
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Reload the page. This is often enough after the Scan Bridge was restarted.
2. If it still happens, check that LexaVault's web address is in the Scan Bridge's list of allowed addresses.
3. Restart the Scan Bridge after changing that list.

### LV-SCN-013 Scan Bridge can't save its state

- **Means:** The bridge can't read or write its working files.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Check that the Scan Bridge's working location exists, has free space and can be written by the bridge.
2. Restart the Scan Bridge.
3. If it reports damaged state, contact support with the reference number before you delete anything.

### LV-SCN-014 Scan Bridge can't start

- **Means:** The bridge stopped at start (address, another bridge running, missing settings).
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Read the Scan Bridge's start-up log: it names the problem.
2. Check that only one Scan Bridge runs on the workstation, that it listens only on the workstation itself, and that LexaVault's address is set.
3. Fix the setting and start the Scan Bridge again.

### LV-SCN-015 LexaVault can't be reached from the scanner

- **Means:** The bridge can't send the scan to LexaVault.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the workstation can reach LexaVault (network, firewall, proxy).
2. Check LexaVault's address and the bridge's sign-in to LexaVault in the Scan Bridge settings.
3. The scan is kept on the workstation; send it again once LexaVault can be reached.

### LV-SCN-016 LexaVault refused the scan

- **Means:** LexaVault refused the scan; its own code says why.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Open the scan's details: LexaVault's own code is there.
2. Look that code up in this guide and follow its steps.
3. Send the scan again from the Scan Bridge once the cause is fixed.

### LV-SCN-017 Scan waiting too long for LexaVault

- **Means:** LexaVault hasn't accepted the scan within the time limit.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that LexaVault is running and taking in work (run the health checks).
2. Look in Support for the case, in case it arrived after all.
3. If it didn't arrive, send the scan again from the Scan Bridge.

### LV-SCN-018 Scan couldn't be sent

- **Means:** Sending failed after 3 tries.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check the network between the workstation and LexaVault, and that LexaVault is running.
2. Send the scan again from the Scan Bridge. The scan is kept on the workstation.
3. If it fails again, give support the reference number.
### LV-SCN-019 Scan can't be cancelled now

- **Means:** The scan is already being sent, or needs attention first.
- **Kind:** Refused
- **Who acts:** Operator
- **HTTP status:** 409

**What to do:**

1. Wait for the scan to finish sending; it then shows as sent or as needing attention.
2. If it needs attention, ask your IT Admin to look at it before you scan the case again.

### LV-SCN-020 Scan resumed after a restart

- **Means:** The Scan Bridge restarted and picked up the scan where it left off.
- **Kind:** Warning
- **Who acts:** Nobody
- **HTTP status:** —

**What to do:**

1. Nothing: the scan is being sent again and LexaVault takes it in only once.
2. If the Scan Bridge restarts often, ask IT to check the workstation.

### LV-SCN-021 Waiting for LexaVault to accept the scan

- **Means:** The scan was sent; LexaVault hasn't confirmed it yet.
- **Kind:** Waiting
- **Who acts:** Nobody
- **HTTP status:** —

**What to do:**

1. Wait: the Scan Bridge checks again by itself.
2. If it waits longer than the time limit, it shows LV-SCN-017; then ask your IT Admin.

## PRC: processing

### LV-PRC-001 PDF can't be opened

- **Means:** The PDF is damaged or protected with a password.
- **Kind:** Stopped
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. If the PDF has a password, save a copy without it.
2. If it is damaged, create it again from the original document.
3. Send it again as a new case or a new revision.

### LV-PRC-002 No readable text

- **Means:** No text could be read from the document.
- **Kind:** Stopped
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Check that the document isn't blank and that its pages show the text clearly.
2. Scan it again at a better quality, or send the original electronic PDF.
3. Send it again.

### LV-PRC-003 Page too poor to read

- **Means:** A page's scan quality is too low to read reliably.
- **Kind:** Stopped
- **Who acts:** Source system
- **HTTP status:** —

**What to do:**

1. Scan the page again: higher resolution, straight, clean, good contrast.
2. Send the document again.
3. If good scans keep failing, ask your IT Admin to check the minimum quality setting.

### LV-PRC-004 Text reading timed out

- **Means:** Reading text from a page took too long.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry processing from Support.
2. If it happens again, check the server's load and the text reader's time limit.
3. Very large or dense pages may need a longer time limit or more capacity.

### LV-PRC-005 Text reader not available

- **Means:** The text reader (OCR) is missing or not set up.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the text reader is installed, with its language data, and that LexaVault can run it.
2. Run the health checks.
3. Retry processing from Support.

### LV-PRC-006 Page couldn't be prepared

- **Means:** A page couldn't be turned into an image for reading.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry processing from Support.
2. If it fails again, the PDF may be damaged: ask the source to send it again.
3. If a good PDF keeps failing, give support the reference number.

### LV-PRC-007 Original document missing

- **Means:** The original file isn't in storage.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that document storage is available (run the health checks).
2. If storage is fine, the original is gone: ask the source to send the document again.
3. Give support the reference number so they can find out why it was missing.

### LV-PRC-008 Processing stopped after repeated failures

- **Means:** Processing failed 3 times, so it stopped.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Open the case's Problem page and look under Technical details for the earlier failure.
2. Fix that cause (look its code up in this guide).
3. Retry processing from Support.

### LV-PRC-009 Document already being processed

- **Means:** Another process is working on this document.
- **Kind:** Stopped
- **Who acts:** Nobody
- **HTTP status:** —

**What to do:**

1. Nothing to do: the document is being processed already.
2. Wait a few minutes and refresh.
3. If the case stays stuck, tell your IT Admin.

### LV-PRC-010 Form type can't be read

- **Means:** The document is a form made for one PDF viewer (XFA), which LexaVault can't read. It was not processed.
- **Kind:** Stopped
- **Who acts:** Processor
- **HTTP status:** —

**What to do:**

1. Ask the sender for the form printed to an ordinary PDF.
2. Open a new case with that file.
3. If it stops again, give your IT Admin the reference number.

## AI: AI model

### LV-AI-001 AI model not available

- **Means:** The AI model couldn't be reached on any server.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the AI model servers are running and can be reached from LexaVault.
2. Run the health checks.
3. Retry processing from Support once a server is back.

### LV-AI-002 AI model timed out

- **Means:** The AI model took too long to answer.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry processing from Support.
2. If it happens often, check the AI servers' load.
3. Add capacity or raise the AI time limit.

### LV-AI-003 AI model busy

- **Means:** The AI model asked LexaVault to slow down.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Wait a few minutes, then retry processing from Support.
2. If it happens often, add AI capacity or lower how much work LexaVault sends at once.

### LV-AI-004 AI model refused the request

- **Means:** The model isn't installed, or the key or settings are wrong.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the model named in the AI settings is installed on every AI server.
2. Check the key and the other AI settings.
3. Retry processing from Support.

### LV-AI-005 AI answer couldn't be used

- **Means:** The model's answer was empty, cut short or not in the expected form.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry processing from Support. A second answer is often fine.
2. If it happens again, check the model and its output limit in the AI settings.
3. If the case is urgent, a reviewer can continue it by hand.

### LV-AI-006 AI model not set up for this entity

- **Means:** This entity has no AI settings.
- **Kind:** Set-up
- **Who acts:** Platform Ops
- **HTTP status:** —

**What to do:**

1. Set up the AI settings for this entity.
2. Ask the IT Admin to retry processing from Support.

### LV-AI-007 AI model couldn't be loaded

- **Means:** Loading the AI model at start failed.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check the model name and that the AI servers can be reached.
2. Check that the model is installed on those servers.
3. Start LexaVault again after the fix.

## RED: redaction

### LV-RED-001 A finding has no location

- **Means:** A confirmed finding has no box on the page, so it can't be blacked out.
- **Kind:** Stopped
- **Who acts:** Reviewer
- **HTTP status:** —

**What to do:**

1. Open the case's review.
2. For that finding, draw its box on the page, or dismiss it if it is not sensitive.
3. Submit the review again.

### LV-RED-002 Redacted copy failed the safety check

- **Means:** The redacted copy didn't pass verification (for example readable text left under a box). It was not exported.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Nothing unsafe left LexaVault: the copy was not exported.
2. Retry processing from Support.
3. If it fails again, give support the reference number. Don't export the document another way.

### LV-RED-003 Redaction couldn't be applied

- **Means:** The PDF engine couldn't apply the redactions.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry processing from Support.
2. If it fails again, the PDF may be damaged: ask the source to send it again.
3. If a good PDF keeps failing, give support the reference number.

### LV-RED-004 Redacted copy couldn't be stored

- **Means:** Storing the redacted copy failed.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that document storage is available and has free space (run the health checks).
2. Retry processing from Support.

### LV-RED-005 A redacted value is in the output text

- **Means:** The final check found a value that was redacted in the text of the redacted copy (for example in the searchable text layer). It was not exported.
- **Kind:** Stopped
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Nothing about the value is recorded; open the case in Support to see which document stopped.
2. Look at the redacted pages: a box may not fully cover the value, or the value may also be printed elsewhere on an untouched page. Add or enlarge the redaction in review and process again.
3. If the searchable text layer is the cause, turn off "Searchable redacted pages" for the entity (Admin › Policy) and retry: redacted pages are then images only.

## REV: review and approval

### LV-REV-001 Someone else already decided this

- **Means:** Another person decided this finding or case first.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 409

**What to do:**

1. Refresh the page to see the decision that was made.
2. If you think the decision is wrong, talk to your supervisor.

### LV-REV-002 Case is no longer in review

- **Means:** The case moved on (approved, resumed, stopped) before this action.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 409

**What to do:**

1. Refresh the page to see where the case is now.
2. You don't need to do anything more on this step.

### LV-REV-003 Findings still open

- **Means:** Decide every finding before continuing.
- **Kind:** Refused
- **Who acts:** Reviewer
- **HTTP status:** 400

**What to do:**

1. Go through the findings and confirm or dismiss each open one.
2. Make sure every confirmed finding has a box.
3. Continue again.

### LV-REV-004 Confirmation needed

- **Means:** Tick the confirmation before you submit.
- **Kind:** Refused
- **Who acts:** Reviewer
- **HTTP status:** 400

**What to do:**

1. Read the confirmation and tick it.
2. Submit again.

### LV-REV-005 Only the assigned reviewer can do this

- **Means:** This review is assigned to someone else.
- **Kind:** Not allowed
- **Who acts:** Reviewer
- **HTTP status:** 403

**What to do:**

1. Check who the review is assigned to.
2. If you should do it, ask your supervisor to assign it to you.

### LV-REV-006 Box not valid

- **Means:** The box is outside the page or has no size.
- **Kind:** Refused
- **Who acts:** Reviewer
- **HTTP status:** 400

**What to do:**

1. Draw the box again inside the page, with a width and a height.
2. Save again.

### LV-REV-007 Can't process again

- **Means:** The original is no longer kept, or the case reached its limit of runs.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 400

**What to do:**

1. This case can't be processed again in LexaVault.
2. If it must be processed again, ask the source to send the document as a new case.

### LV-REV-008 Can't continue by hand

- **Means:** Continuing manually is only for cases stopped at the AI step.
- **Kind:** Refused
- **Who acts:** Reviewer
- **HTTP status:** 400

**What to do:**

1. Check on the Problem page where the case stopped.
2. If it stopped somewhere else, follow the code shown there, or ask your IT Admin to retry processing.
### LV-REV-009 Page doesn't exist

- **Means:** The page asked for isn't in the document.
- **Kind:** Refused
- **Who acts:** Nobody
- **HTTP status:** 400

**What to do:**

1. Reload the review page; it then shows only the document's own pages.
2. If it happens again, give IT the reference number shown.

## DLV: export

### LV-DLV-001 Destination can't be reached

- **Means:** LexaVault couldn't connect to the destination.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the destination is running and that LexaVault can reach it (network, firewall).
2. Run the connection test under Integrations.
3. Retry the export from Support.

### LV-DLV-002 Destination busy or down

- **Means:** The destination answered "busy" or with a server error.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Wait a few minutes, then retry the export from Support.
2. If it keeps failing, contact the destination's team with the time and the reference number.

### LV-DLV-003 Destination refused the sensitivity level

- **Means:** The destination doesn't accept the case's or a document's sensitivity level.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Look under Technical details for the destination's own code and the level it refused.
2. Ask the destination's team whether it should accept that level, and change its set-up if so.
3. After the fix, choose **Retry processing** in Support.

### LV-DLV-004 Destination refused the file

- **Means:** The destination refused the case or a document. Its own code is in the details.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Look under Technical details for the destination's own code.
2. Look that code up in the destination's own documentation, or ask its team.
3. Fix the cause, then send the case again from Support.

### LV-DLV-005 Sign-in to the destination failed

- **Means:** The destination refused LexaVault's credentials, or the credential can't be found.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the credential is stored where the connection's settings say it is.
2. Check with the destination's team that the credential is still valid and has the right access.
3. Run the connection test under Integrations, then retry the export from Support.

### LV-DLV-006 Destination not set up or switched off

- **Means:** There is no enabled destination, or its address isn't valid.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Under Integrations, check that this entity has an enabled destination.
2. Check that its address is a valid web address (http or https).
3. Switch it on or correct it, then retry the export from Support.

### LV-DLV-007 Destination's answer not understood

- **Means:** The destination's answer was missing information or not in the expected form.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check that the destination runs a version LexaVault supports.
2. Contact the destination's team with the time and the reference number.
3. Retry the export from Support after the fix.

### LV-DLV-008 Not every document was confirmed

- **Means:** The destination didn't confirm all documents of the case.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check in the destination which documents of the case arrived.
2. Retry the export from Support.
3. If it happens again, contact the destination's team with the reference number.

### LV-DLV-009 Already received by the destination

- **Means:** The destination already has this case. Counted as exported.
- **Kind:** Warning
- **Who acts:** Nobody
- **HTTP status:** —

Reserved: not used yet.

**What to do:**

1. Nothing to do: the case counts as exported.
2. If you expected a newer version to arrive, check in the destination which version it holds.

### LV-DLV-010 Export folder already exists

- **Means:** A folder for this case run is already in the export location.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Look in the export location for the folder of this case run, left by an earlier attempt.
2. Check whether it is complete, then remove or move it.
3. Send the case again from Support.

### LV-DLV-011 Nothing ready to export

- **Means:** A document isn't finished, or its redacted copy is missing.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Check in the case panel that every document finished processing.
2. If a redacted copy is missing, retry processing from Support.
3. Then retry the export.

### LV-DLV-012 Split rule matched more than once

- **Means:** A page matched more than one split rule.
- **Kind:** Set-up
- **Who acts:** Business Admin
- **HTTP status:** —

**What to do:**

1. Look at the split rules: one page matched more than one of them.
2. Make the rules more specific, so each page matches only one.
3. Ask your IT Admin to retry the export.

### LV-DLV-013 Split document couldn't be made

- **Means:** Finding the split pages or building the split PDF failed.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Retry from Support.
2. If it fails again, check that document storage is available and that the split rules are complete.
3. If it still fails, give support the reference number.

### LV-DLV-014 Can't retry this failure

- **Means:** This failure needs a fix first; retrying won't help.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Look under Technical details for the code of the original failure.
2. Fix that cause (look its code up in this guide).
3. After the fix, choose **Retry processing** in Support.

### LV-DLV-015 Resend failed

- **Means:** A person's resend failed, so the case went to the IT error queue.
- **Kind:** Export failed
- **Who acts:** IT Admin
- **HTTP status:** —

Reserved: not used yet.

**What to do:**

1. Open the case in the IT error queue.
2. Look under Technical details for the code of the failure, and follow its steps in this guide.
3. Send the case again once the cause is fixed.

## AUT: sign-in and access

### LV-AUT-001 Please sign in again

- **Means:** The session ended or the sign-in can't be checked.
- **Kind:** Not allowed
- **Who acts:** Person
- **HTTP status:** 401

**What to do:**

1. Sign in again.
2. If it keeps asking, close all LexaVault tabs and sign in once more.
3. If it still happens, tell your IT Admin and give the reference number.

### LV-AUT-002 No access to LexaVault

- **Means:** You're signed in, but not given a LexaVault role.
- **Kind:** Not allowed
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Check the person's groups in the login system.
2. Check under Sign-in that one of those groups gives a LexaVault role, and add the person to the right group.
3. Ask the person to sign out and sign in again.

### LV-AUT-003 No access to this entity

- **Means:** You can't open this entity, or it was removed from your access.
- **Kind:** Not allowed
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Check that the person's groups give access to this entity.
2. Check that the entity is switched on.
3. Ask the person to sign out and sign in again.

### LV-AUT-004 Choose an entity

- **Means:** You have access to more than one entity; choose one.
- **Kind:** Refused
- **Who acts:** Person
- **HTTP status:** 403

**What to do:**

1. Choose the entity you want to work in from the list.
2. You can switch entity later with the entity switch at the top right.

### LV-AUT-005 Entity not active

- **Means:** This entity is switched off or not set up yet.
- **Kind:** Not allowed
- **Who acts:** Platform Ops
- **HTTP status:** 403

**What to do:**

1. Check the entity's status.
2. Finish its set-up or switch it on, if it should be in use.
3. Tell its users once it is active.

### LV-AUT-006 Not allowed

- **Means:** Your role doesn't allow this action.
- **Kind:** Not allowed
- **Who acts:** Business Admin, IT Admin
- **HTTP status:** 403

**What to do:**

1. Check which role the person has and which role the action needs.
2. If the person should be able to do it, give them that role.
3. Ask the person to sign out and sign in again.

### LV-AUT-007 Sign-in isn't working right now

- **Means:** The login system's settings don't match LexaVault's, or it can't be reached. The IT Admin has the details.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Find the reference number in LexaVault's log: it gives the exact reason.
2. Compare the login system's settings with LexaVault's under Sign-in (issuer, audience, groups).
3. Check that LexaVault can reach the login system, then ask the person to sign in again.

### LV-AUT-008 Integration client not allowed

- **Means:** The source system's client isn't linked to an enabled connection for this entity, or is using another connection's case.
- **Kind:** Not allowed
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Under Integrations, check that the source system's client is linked to an enabled connection for this entity.
2. Check that the source only works on cases its own connection created.
3. Ask the source to send again.

### LV-AUT-009 Sign-in didn't complete

- **Means:** The browser's sign-in with the login system failed part way.
- **Kind:** Not allowed
- **Who acts:** Person, IT Admin
- **HTTP status:** —

**What to do:**

1. Person: go back to the LexaVault start page and sign in again.
2. If it fails again, close all LexaVault tabs and try once more.
3. IT Admin: if it keeps failing, check LexaVault's settings in the login system and under Sign-in.

### LV-AUT-010 Feature not switched on

- **Means:** This feature isn't switched on for your entity.
- **Kind:** Set-up
- **Who acts:** Platform Ops
- **HTTP status:** 403

**What to do:**

1. Check whether this entity should have the feature.
2. If so, switch it on for the entity.
3. If not, tell the person it isn't available for their entity.

## LIC: licence

### LV-LIC-001 No licence installed

- **Means:** No licence is stored for this installation.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Ask your IT team to store the licence file you received, with the licence command (install guide: Setup).
2. Ask sources to send again any cases that were refused meanwhile.

### LV-LIC-002 Licence expired

- **Means:** The licence has expired, its grace period is over, or it hasn't started yet. No new cases are taken in; cases already in LexaVault are finished.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. See the licence's dates under Admin, Licensing.
2. Get a renewed licence from your supplier and ask your IT team to store it with the licence command. No restart is needed.
3. Ask sources to send again any cases that were refused meanwhile. Watched folders and polling go on by themselves.

### LV-LIC-003 Licence not valid

- **Means:** The licence can't be read or trusted, or it doesn't cover this entity.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Ask your IT team to read the licence's own code in Support, Status.
2. Store the original licence file again with the licence command. If the entity isn't in it, ask your supplier for a licence that lists the entity.
3. If it is still refused, get a new licence file from your supplier.

### LV-LIC-004 Licence doesn't include this

- **Means:** The licence doesn't cover LexaVault.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. See what the licence covers under Admin, Licensing.
2. Ask your supplier for a licence that covers LexaVault, and ask your IT team to store it with the licence command.

## CFG: settings

### LV-CFG-001 Setting not valid

- **Means:** A value breaks its rule; the message names the field and the allowed range.
- **Kind:** Refused
- **Who acts:** Admin who saved it
- **HTTP status:** 400

**What to do:**

1. Read the message: it names the field and the allowed range.
2. Correct the value.
3. Save again.

### LV-CFG-002 Case fields not set up

- **Means:** The entity needs active case fields with exactly one Primary.
- **Kind:** Set-up
- **Who acts:** Business Admin
- **HTTP status:** 400

**What to do:**

1. Open Case fields.
2. Make sure at least one case field is active and exactly one is marked Primary.
3. Save, then ask sources to send again any cases that were refused.

### LV-CFG-003 Connection settings not valid

- **Means:** A connection's settings are missing, duplicated or not allowed (for example a private address).
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Read the message: it says which setting is wrong.
2. Fill in what is missing, use a name and client that no other connection uses, and use an address LexaVault is allowed to call.
3. Save again.

### LV-CFG-004 Connection test failed

- **Means:** Testing the connection failed; the details say at which step.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Read the details: they say at which step the test failed.
2. Fix that step (address, sign-in, network, or the other system itself).
3. Run the test again.

### LV-CFG-005 More than one main export destination

- **Means:** Only one enabled main export destination is allowed per entity.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 409

**What to do:**

1. Under Integrations, decide which connection is this entity's main export destination.
2. Switch the other one off.
3. Save again.

### LV-CFG-006 Split rule not valid

- **Means:** A split rule is incomplete, duplicated or points to a missing destination.
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Fill in every field of the rule, and remove any rule that repeats another.
2. Point the rule to a destination that exists, is switched on and sends out.
3. Save again.

### LV-CFG-007 Watched folder not set up for this entity

- **Means:** The watched folder belongs to another entity or isn't set up.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Check which entity the watched folder is set up for.
2. If it should be this entity, set it up for this entity.
3. Otherwise, work with the watched folder from the entity it belongs to.

### LV-CFG-008 Login system settings not valid

- **Means:** The draft login settings are incomplete or failed their test.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 400

**What to do:**

1. Under Sign-in, complete the draft login settings.
2. Run the test and fix what it reports (for example the issuer address).
3. Activate the draft once the test passes.

### LV-CFG-009 Scanner settings not valid

- **Means:** A scanner setting is out of range.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 500

**What to do:**

1. Check the scanner settings in the Scan Bridge (source, resolution, page limit, time limit) against their allowed ranges.
2. Correct the value that is out of range.
3. Restart the Scan Bridge.

### LV-CFG-010 Connection switched off

- **Means:** The connection is disabled or not of the right kind.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** 403

**What to do:**

1. Under Integrations, check that the connection is switched on.
2. Check that it is the right kind of connection for this action (for example a polling source).
3. Try again.
### LV-CFG-011 Support action not possible now

- **Means:** The case or incident isn't in a state that allows this action (for example the incident is closed).
- **Kind:** Refused
- **Who acts:** IT Admin
- **HTTP status:** 409

**What to do:**

1. Reload the page to see the case's or incident's current state.
2. Holds and restarts are only for cases that stopped and were never exported; a closed incident allows no more actions.
3. If the case needs work again, open it from Support while it still shows a problem.

## SYS: system

### LV-SYS-001 Something went wrong

- **Means:** An unexpected error. Give IT the reference number shown.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** 500

**What to do:**

1. If you are not IT: try once more, and if it fails again, give IT the reference number and the time.
2. IT Admin: find the reference number in LexaVault's log to see what failed.
3. If the log doesn't explain it, give support the reference number.

### LV-SYS-002 Storage not available

- **Means:** LexaVault can't read or write its document storage.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Check that the document storage is reachable, has free space and can be written by LexaVault.
2. Run the health checks.
3. Retry the failed work from Support, and ask sources to send again anything that was refused.

### LV-SYS-003 Database not available

- **Means:** LexaVault can't reach its database.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Check that the database is running and can be reached from LexaVault.
2. Run the health checks once it is back.
3. Retry the failed work from Support.

### LV-SYS-004 Too much work queued

- **Means:** LexaVault couldn't take more work just now.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** 503

**What to do:**

1. Wait a few minutes, then try again.
2. If it happens often, check the server's load and add capacity.

### LV-SYS-005 Start-up setting wrong

- **Means:** LexaVault stopped at start because a setting is wrong; the log names it.
- **Kind:** Set-up
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Read LexaVault's start-up log: it names the setting.
2. Correct the setting.
3. Start LexaVault again.

### LV-SYS-006 Health check failed

- **Means:** A part LexaVault needs (AI model, text reader, database, storage, destination) failed its check.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. See which part failed its check.
2. Follow the steps for that part: AI model LV-AI-001, text reader LV-PRC-005, database LV-SYS-003, storage LV-SYS-002, destination LV-DLV-001.
3. Run the health checks again.

### LV-SYS-007 Temporary file left behind

- **Means:** A temporary file couldn't be deleted after a failure; IT should remove it.
- **Kind:** System
- **Who acts:** IT Admin
- **HTTP status:** —

**What to do:**

1. Find the reference number in LexaVault's log: it gives the temporary file's id. The file is in LexaVault's temporary folder.
2. Delete the file safely. It may hold document content.
3. Check why it couldn't be deleted (access rights, a file still in use).

### LV-SYS-008 Item not found

- **Means:** The item asked for (a rule, a connection, a file) doesn't exist in this entity.
- **Kind:** Refused
- **Who acts:** Person, IT Admin
- **HTTP status:** 404

**What to do:**

1. Reload the page: the item may have been removed by someone else.
2. Check that you are working in the right entity.
3. If the item should be there, give IT the reference number shown.

### LV-SYS-009 Unknown request

- **Means:** The address or method isn't one LexaVault offers.
- **Kind:** Refused
- **Who acts:** Source system
- **HTTP status:** 404 / 405

**What to do:**

1. Check the address and HTTP method against LexaVault's API documentation.
2. Fix the call in the source system and send it again. Nothing was taken in.
