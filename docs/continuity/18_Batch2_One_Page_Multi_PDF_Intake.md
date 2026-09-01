# LexaVault Batch 2 — One-Page Multi-PDF Intake

Date: 2026-09-01  
Baseline: user-accepted Batch 1 role/capability + Technical Attention package  
Status: user build/runtime accepted on 2026-09-01; frozen Batch 3 baseline

## Approved behavior

The normal manual intake flow is one workspace containing:

- tenant-configured business identifiers;
- drag-and-drop for desktop;
- `Browse Files` as an equal fallback;
- a scan-ready `Scan Documents` source action;
- one shared PDF document queue;
- a visible `Submit Documents` action.

The final action is disabled until all tenant-required identifiers are present and at least one valid PDF is queued. Selecting a file never starts processing.

## Document queue

- Multiple PDFs may be added in one intake.
- Every PDF remains a separate document and retains its original filename.
- PDFs are never merged and no `-redacted` suffix is introduced.
- Browse, drop, and scan-ready selections enter the same queue.
- Duplicate selections are skipped using local file identity metadata.
- Non-PDF selections are rejected before package creation.
- A queued PDF may be removed before the final submit action.
- Queue rows show filename, size, source, upload state, and a safe per-file failure message.

`Scan Documents` is the V1 integration point for scanner/native capture that produces PDFs. It intentionally feeds the same queue and downstream pipeline; it does not create a second business workflow. Device-specific scanner/camera acquisition can be connected incrementally without redesigning the intake page.

## Submission boundary

Before `Submit Documents`, identifiers and PDFs remain a browser draft. The explicit action uses the existing APIs in this order:

1. create one tenant-scoped `MANUAL_UPLOAD` Intake Batch;
2. upload each queued PDF sequentially while the package remains `CREATED`;
3. call the existing submit endpoint only after every PDF succeeds;
4. allow the existing processing orchestrator to begin extraction/OCR/AI/redaction/review/delivery.

Sequential upload avoids concurrent aggregate-update conflicts and preserves a deterministic document queue. If any PDF fails, submit is not called and processing does not start. Successfully uploaded PDFs remain attached to the incomplete intake; failed PDFs may be retried, removed, or replaced. Business identifiers lock after server package creation so the persisted Business Record identity cannot silently diverge from the page.

## Scope boundaries

Batch 2 does not add or change:

- database schema or Liquibase changesets;
- manual-intake REST endpoints;
- API-submission semantics;
- watched-folder trigger/fingerprint behavior;
- document processing, AI policy/provider routing, OCR, detection, redaction, review, export, or retention;
- Batch 1 role/capability or Technical Attention behavior;
- Reports or breadcrumb/navigation work.

The existing upload controls on an already-created `CREATED` package remain a recovery path. The standard `/packages/new` flow no longer navigates users through that second screen.

## Focused manual verification

1. Open `Upload Documents`; verify identifiers, document sources, queue, and disabled `Submit Documents` appear on one page.
2. Confirm the submit action remains disabled when a required identifier is empty.
3. Add three or more PDFs through `Browse Files`; verify all appear separately with original filenames.
4. Remove one queued PDF before submission and confirm it disappears without creating/processing a package.
5. Drag multiple PDFs into the drop zone; verify they join the same queue.
6. Use `Scan Documents` to select a scanner-produced PDF; verify it joins the same queue with source `Scan Documents`.
7. Select a non-PDF and confirm it is rejected without blocking already-valid PDFs.
8. Select the same PDF twice and confirm the duplicate is skipped.
9. Complete required identifiers and submit; verify one Intake Batch contains every queued PDF as separate documents.
10. Verify processing starts only after `Submit Documents`, then follows the existing Dashboard/Manual Review/Technical Attention lifecycle.
11. Simulate one upload failure; verify processing does not start, the failed row is retryable/removable, and successful uploads are not duplicated on retry.
12. Repeat core Batch 1 role visibility and one known 61-page precision regression before acceptance.
