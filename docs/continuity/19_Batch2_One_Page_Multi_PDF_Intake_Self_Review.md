# LexaVault Batch 2 — One-Page Multi-PDF Intake Self-Review

Date: 2026-09-01  
Implementation owner: ChatGPT  
Status: user build/runtime accepted on 2026-09-01; frozen Batch 3 baseline

## Implemented delta

- Replaced the normal two-screen manual intake orchestration with one responsive workspace.
- Preserved tenant-configured identifier labels, ordering, required markers, and Primary indicator.
- Added multi-select `Browse Files`, desktop drag-and-drop, and `Scan Documents` actions.
- Routed every source into one draft PDF queue with filename, size, source, status, and removal control.
- Rejected non-PDF files and skipped duplicate selections before package creation.
- Kept `Submit Documents` visible from the beginning and disabled until configuration, required identifiers, and document readiness are satisfied.
- Deferred package creation and all uploads until the explicit submit action.
- Uploaded documents sequentially and called submit only when all uploads succeeded.
- Added partial-failure recovery without re-uploading successful documents.
- Locked identifiers after server-side package creation while allowing failed documents to be retried, removed, or replaced.
- Added touch-friendly responsive layout for desktop, iPad/touch, and narrow screens.

## Backend contract protection

`Batch2MultiDocumentIntakeContractTest` verifies that three PDFs:

- stay as three documents with their original filenames;
- leave the package in `CREATED` before explicit submit;
- transition the package to `SUBMITTED` only when `submit()` is invoked;
- preserve document count and boundaries after submission.

No production backend class or endpoint changed because the accepted API already implements the required lifecycle safely.

## Verification completed in this workspace

- Frontend `npm ci`: PASS.
- Strict TypeScript `tsc --noEmit`: PASS.
- Vite production build: PASS.
- Source review confirms no Batch 1, API intake, watched-folder, AI, OCR, detection, redaction, review, delivery, or retention production files changed.

## Verification not executable in this workspace

The available runtime contains an OpenJDK 17 JRE only. It has no `javac`, Java 21 JDK, Maven, or Maven wrapper. Therefore the new Java contract test and full Maven reactor were not executed here. This is an environment limitation, not a reported Java test pass. Run the normal Java 21 `build-all.cmd` / `Build-LexaVault-All.ps1` workflow before manual acceptance.

## No-regression assessment

- Existing REST contracts are reused unchanged.
- Tenant identifier validation remains backend-authoritative.
- Tenant scoping and capability enforcement remain unchanged.
- Package processing still begins only through the existing submit endpoint.
- Original filenames and separate document boundaries are preserved.
- API and watched-folder intake paths are untouched.
- Dashboard polling, Manual Review scoping/page preview/final-item async resume, Technical Attention/selective retry, AI routing, precision rules, redaction, export, and retention are untouched.
- No schema migration or duplicate cleanup scheduler was added.

## User acceptance

The user reported Batch 2 successful on 2026-09-01 after testing the corrected package. The accepted one-page multi-PDF intake, drag/drop presentation, shared scan-ready queue, document-boundary behavior, and submit gating are now frozen no-regression behavior for Batch 3.

## Packaging correction — 2026-09-01

The first Batch 2 ZIP reused an existing archive path during packaging. Although the working source and production frontend build contained the Batch 2 intake CSS, the ZIP retained the earlier `index.css` entry. Runtime evidence showed the new React component with unstyled native file inputs, no visible dashed drop zone, and none of the intended queue/sticky-submit presentation.

The replacement package is recreated under a new filename rather than updating the prior archive. Intake styling now lives in the dedicated `CreatePackagePage.css`, imported directly by `CreatePackagePage.tsx`, so the component and styles cannot be separated by a stale shared-stylesheet archive entry. Verification must confirm the archived CSS contains `.intake-drop-zone`, `.intake-hidden-input`, `.intake-document-queue`, and `.intake-submit-panel`; native file inputs must be hidden in the browser.
