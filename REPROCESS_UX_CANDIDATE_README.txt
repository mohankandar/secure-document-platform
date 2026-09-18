LexaVault V1 — Reprocess UX Cleanup candidate

Scope only:
- explicit confirmation before Reprocess from Retained Original;
- accepted reprocess returns promptly and runs through the existing asynchronous processing dispatcher;
- package leaves COMPLETED immediately and UI presents SUBMITTED/PROCESSING as REPROCESSING for an existing export lineage;
- reprocess action is unavailable while the package is not COMPLETED;
- backend still enforces completed-package eligibility independently of UI state;
- successful re-export continues to reset retention through the existing lifecycle/export-cycle rules;
- existing audit, incident, review, approval, tenant isolation, R11 accuracy, AI resilience and Support Console behavior are unchanged.

Focused local validation:
1. Run build-all.cmd.
2. As a role with PACKAGE_REPROCESS, open an eligible COMPLETED package with retained original.
3. Click Reprocess from Retained Original; verify confirmation appears and Cancel performs no action.
4. Confirm Reprocess; verify the request returns promptly, status leaves COMPLETED and displays REPROCESSING/Processing.
5. Verify Reprocess action is unavailable during the active cycle, including after browser refresh.
6. Attempt a duplicate POST while the package is active; verify it is rejected and no second cycle starts.
7. Verify normal review/final-approval/failure handling still applies if encountered.
8. Verify successful completion increments the export cycle and retention dates are reset by the normal successful-export lifecycle.
9. Verify Standard User/Document Processor remains unauthorized for PACKAGE_REPROCESS.
10. Confirm Liquibase remains 52 changeSets.

No new migration and no performance/concurrency optimization are part of this candidate.
