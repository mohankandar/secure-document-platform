# Report U005: New case (finding 20)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE, with one gap. The rule line shows only the limit the server actually enforces. Manual upload has no page limit and no files-per-case limit today, so those two parts are left out (see the question).

## What was done

1. **Rules before upload.** Under "Drop PDFs here": "PDF only · up to 50 MB each".
   - **Where the 50 MB comes from:** `spring.servlet.multipart.max-file-size: 50MB` in `backend/securedoc-api/src/main/resources/application.yml`. A larger file is refused with `FILE_TOO_LARGE` (`GlobalExceptionHandler`). PDF only: `UploadDocumentService` accepts only `application/pdf`.
   - The screen has no endpoint that reports the limit, so the value is repeated in `src/studio/intakeRules.ts`, with a comment naming the setting.
   - **Not shown:** pages per file and files per case. The server enforces neither on manual upload (`UploadDocumentService`, `PackageController`). Only machine intake has per-source limits. Showing the design's "500 pages" and "50 files" would mean stating rules the server does not have.
2. **Files listed with size and page count; refused files marked in red.**
   - Each row shows its size and pages ("2 pages").
   - The page count is read in the browser from the PDF's page tree. It **fails closed** and shows "— pages" when it cannot be read with certainty: a compressed page tree, or an edited file whose page trees disagree. The server still counts pages itself.
   - Files the server would refuse are now listed in red with what to do, instead of being skipped quietly:
     - "Not a PDF — remove it or convert it first": the name or type is not PDF, or the file does not start with `%PDF-` within its first 1024 bytes (this includes empty files and renamed files);
     - "Over 50 MB — remove it or split it first".
   - **Create case stays disabled** while any file is marked, and while a file is still being read. The footer says "Fix the file marked in red to continue." Submit also refuses in code, not only through the button.
   - Duplicates are still skipped, with the notice.
3. **Button text "Create case"** on both Upload and Scan (was "Open case & submit"). "Retry failed uploads" and "Submit case" stay for a case that was already opened as a draft.
4. **Step 2 description:** "Upload PDFs you already have, or scan paper." ("straight into the vault" removed.)
5. **Scan tab:**
   - The bridge configuration offers no friendly name, so the scanner shows as "Scanner at HOME-LAB" (its site), with "Ready" (or Offline and so on).
   - Device ID, site ID and free space are under "Technical details", closed by default.
   - The only name-like bridge setting, `physical.deviceName`, is the WIA device to select. It is not sent to the browser and is not a display name.
6. **Pre-fill with `?id=<value>`** for the primary identifier. "Start a new case for this …" (U003) now opens `/intake?id=…`. The older `?for=` is still read, so an open tab or bookmark keeps working.

## Commands run and results

- `npm run build`: passed.
- `npm run test:standalone`: passed (`test:auth` 8, `test:vocabulary` 26 (7 new), `test:ui-framework` 17, `test:oidc-signin` 30 (4 new)). Final run after the last change.
- **One failed run:** the first run of the new browser test failed on my own check. "No 'vault' on the screen" matched "LexaVault". I narrowed it to the word on its own (`/\bvault\b/i`); the screen was right.
- **Local app:**
  - started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. As in U004, the script stopped with "Frontend did not become ready on localhost:5173", but the frontend and the scan bridge answered and every check ran;
  - New case checked as **Document Processor** and **Document Supervisor**, light and dark, 1440 and 1024 px, in Edge (Playwright):
    - Upload tab with a 2-page fake PDF and a fake `.tif`. The PDF showed "2 pages"; the `.tif` was in red with "Not a PDF — remove it or convert it first"; Create case was disabled.
    - Scan tab showed "Scanner at HOME-LAB" and "Ready · Simulator", with device, site and free space under Technical details.
    - No page errors.
  - Fixed after looking at the screens: the friendly scanner name was in the monospace font meant for ids, and a refused file showed a pointless "— pages".
  - Create case was not pressed, so no case was added to the local data. The fake files were made in memory by the test script; none were saved or committed.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Backend:** not changed, so the backend build was not rerun (440 tests at U004).
- **Rules:** no raw hex outside `tokens.css`, no new breakpoint, no new dependency. `uiFrameworkContract.test.ts` is unchanged.

## Tests: counts per module, and any failures with their output

- **New `src/studio/intakeRules.test.ts` (node, 7):**
  - the rule line, and 50 MB as 50 × 1024 × 1024 bytes;
  - refused files: not PDF by name or type, not PDF inside (zip bytes, empty file), over 50 MB, exactly 50 MB accepted, and the wording;
  - the PDF header within the first 1024 bytes, not later;
  - the page count from a flat and a nested page tree;
  - the page count fails closed for a compressed tree, two disagreeing roots, a missing count and a zero count;
  - `?id=` and the older `?for=`, and blank values;
  - the scanner name.
- **New `e2e-oidc/newCase.spec.ts` (Playwright, mocked API and scan bridge, 4):**
  - the rule line, the step-2 text, no "vault", and no "Open case & submit";
  - a PDF with "2 pages" and a `.tif` in red; Create case is disabled with "Fix the file marked in red to continue."; removing it enables Create case; nothing was sent to the server meanwhile;
  - a `.pdf` with non-PDF content is marked;
  - `?id=LN204901` fills the loan number;
  - the Scan tab: "Scanner at HOME-LAB", the device id is not on the scanner line, and device, site and free space are under Technical details.
- No existing test was changed or weakened.

## Files changed

- **New:** `src/studio/intakeRules.ts`, `src/studio/intakeRules.test.ts`, `e2e-oidc/newCase.spec.ts`.
- **Changed:**
  - `src/studio/UploadCapture.tsx` (rules line, size and pages, refused files, Create case);
  - `src/studio/ScanCapture.tsx` (scanner name, Technical details, Create case);
  - `src/studio/Intake.tsx` (step-2 text, `?id=`);
  - `src/studio/CaseDrawer.tsx` (the link uses `?id=`);
  - `src/studio/studio.css`;
  - `package.json` (`test:vocabulary` also runs `intakeRules.test.ts`).

All paths are under `frontend/securedoc-ui/`.

## Commits (hash and message), or "not committed"

- See the commit "feat(ui): New case states the upload rules, marks refused files, Create case (UI task U005)", which includes this report.

Not pushed, not merged.

## Not done, and why

1. **"<pages> pages each" and "up to <n> files per case"** are not shown. The server has no such limits on manual upload, and the screen should not state rules the server does not enforce.

## Questions for the owner

1. **Pages per file and files per case:** should manual upload get real limits? The design suggests 500 pages and 50 files. That would be a backend change to V1 upload (a frozen area), enforced by the server and then shown on this screen, so it would go in a backend task after the UI series. Or should the rule line stay as it is now, "PDF only · up to 50 MB each"?

## Suggested next step

U006.

## Owner's answers (2026-11-18)

1. **Pages per file and files per case:** keep the rule line as it is now, "PDF only · up to 50 MB each". Real server-enforced limits (the design's 500 pages per file and 50 files per case) go in the backend work after the UI series, because they change V1 upload (a frozen area). The screen shows them only once the server enforces them. Nothing to change now.

## What was done with the answers (2026-11-18)

- Answer 1: nothing changed. The rule line stays "PDF only · up to 50 MB each". Server-enforced pages-per-file and files-per-case limits are noted for the backend work after the UI series.
