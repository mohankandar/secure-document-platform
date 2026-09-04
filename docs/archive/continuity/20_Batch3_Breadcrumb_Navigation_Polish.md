# LexaVault Batch 3 — Breadcrumb and Navigation Polish

Date: 2026-09-01  
Baseline: user-accepted Batch 1 and corrected Batch 2 package  
Status: implemented in source; user build/runtime verification pending

## Approved scope

Batch 3 adds one consistent breadcrumb pattern so desktop and iPad/touch users are not dependent on browser Back or only the left navigation.

Approved contextual patterns are implemented as:

- `Dashboard > Contract 000282600095 > Manual Review` for package-scoped review when the configured primary identifier is `Contract Number`;
- `Dashboard > Contract 000282600095 > Document 000282600095.pdf` for a document-specific Technical Attention record;
- `Dashboard > Manual Review` for the tenant-wide review queue;
- `Dashboard > [current page]` for top-level Upload Documents and Reports;
- `Dashboard > Administration > [current page]` for administration screens.

The package breadcrumb uses the tenant-configured, displayable primary business identifier. `Contract Number` is presented concisely as `Contract`; other configured labels remain tenant-defined. Fallback text is `Intake Details`. Internal package, document, finding, and task UUIDs are never exposed as business-facing breadcrumb labels.

## Navigation behavior

- `Dashboard` is a link on every non-Dashboard route.
- A contract/intake segment links to its existing Intake Details route when it is a parent of Manual Review or Technical Attention.
- The current page is plain text with `aria-current="page"` rather than a redundant self-link.
- Breadcrumbs use semantic `nav` and ordered-list markup with an accessible label.
- Keyboard focus is visible.
- Links have a minimum 44-pixel touch target on coarse pointers and narrow screens.
- Long tenant identifiers and filenames truncate visually without changing the full source value used to render the label.
- No fake document-detail link or new document business workflow was added. The original filename is shown as current document context only where the existing Technical Attention response identifies that document.

## Route coverage

The shared component is used on:

- Upload Documents;
- Intake Details, including its loading state;
- global and package-scoped Manual Review, including loading state;
- Technical Issue Details, including loading state;
- Reports;
- Business Admin;
- Tenant IT / Platform Operations Technical Administration, including loading state;
- Audit;
- permission-denied surfaces.

Dashboard remains the root and therefore does not display a self-only breadcrumb.

## Scope boundaries

Batch 3 does not add or change:

- backend endpoints, authorization, role/capability mappings, tenant boundaries, or API payloads;
- package, document, review, retry, processing, AI, OCR, detection, redaction, delivery, or retention behavior;
- the accepted Batch 2 upload/drag/drop/scan-ready queue or submission boundary;
- dashboard action menus, removal confirmation semantics, or retention-owned physical cleanup;
- Reports content or requirements;
- a new document-details route;
- database schema or Liquibase changesets.

## Focused manual verification

1. Open Upload Documents and verify `Dashboard > Upload Documents`; select Dashboard and confirm navigation to the Dashboard.
2. Open an intake with primary identifier `Contract Number = 000282600095`; verify `Dashboard > Contract 000282600095` and no UUID appears.
3. From that intake open Manual Review; verify `Dashboard > Contract 000282600095 > Manual Review`, then select Contract and return to the same Intake Details page.
4. Open Manual Review from the left navigation; verify `Dashboard > Manual Review` and the queue remains tenant-wide.
5. Open a document-specific Technical Issue; verify `Dashboard > Contract 000282600095 > Document [original filename]`. Select Contract and return to the intake.
6. For a package-level issue without a document ID, verify the final segment reads `Technical Issue`.
7. Verify Upload, Reports, Business Admin, Technical Administration, Audit, and permission-denied screens all use the same visual breadcrumb pattern.
8. Use Tab/Shift+Tab and keyboard activation to verify parent links and visible focus.
9. Test iPad/touch or responsive emulation at 640 pixels or narrower; verify targets are comfortably touchable, trails wrap, and long labels do not overflow the page.
10. Recheck role/capability visibility and direct-route backend denial; breadcrumbs must not grant access.
11. Recheck Batch 2 with five PDFs in one intake; drag/drop, separate queue rows, original filenames, and submit gating must remain unchanged.
12. Recheck one package-scoped exact-page Manual Review and one selective Technical Attention retry to confirm navigation-only scope.
