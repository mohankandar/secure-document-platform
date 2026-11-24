# Report 0066: manual continue for unreadable pages, and the rejection reason

Date: 2026-11-24
Branch: `feature/0066-unreadable-pages`, from the integration branch
Outcome: **STOPPED** before any product code (queue rule 6: it needs decisions the task doesn't give)

## What was found (read from the code, nothing changed)
1. **There is no reject step.** The task adds a reason to "Review reject, Approval reject". Neither exists: Review has confirm and dismiss per finding, and completing a review task only approves (`ReviewWorkflowService`); Approval has only approve-delivery. `DocumentPackage.reject()` and `ReviewTask.reject()` exist in the domain and nothing calls them. No screen has a Reject button, and the compliance report hides its "Rejected" rows for this reason (task 0018). BL8 in the requirements says "**when** a reject step is designed".
2. **Review can't hold a page with no findings.** It is a queue of findings, with one box per finding. A page reviewed by hand needs any number of boxes, which only Continue manually can draw today.
3. **There is no "removing pages" policy**, and the final check stops a document whose output has a different page count from its source (`LV-RED-002`). "Not offered without the policy" therefore means never offered.
4. **Text stops at the first unreadable page** and does not read the pages after it, so the list of unreadable pages is not known when the case stops.
5. Continue manually is refused unless the stop was at AI analysis, and needs the document's pages, which are saved only when Text succeeds.

## Commands run and results
Searches and reading only. No build or test run: no code changed.

## Files changed
`docs/work/0066-task.md`, `docs/work/0066-report.md`.

## Questions for the owner
1. **Reject:** build it now? Recommended: yes, as its own task. Review and Approval get "Reject case" for the people who may review or approve today; the case ends as Rejected, nothing is exported, the files follow normal retention, and it can't be undone (the source sends a new case). Who may reject, and does anyone get told?
2. **Reason list:** recommended new codes: wrong document, unreadable, out of scope, duplicate, other (see ticket). "The export/notification carries the code": a rejected case has no export, so where should it go? Recommended: the audit trail, the Cases list and the API status answer.
3. **A page checked by hand:** recommended: in the existing Continue manually screen (it has the boxes and the "I have reviewed" confirmation), limited to the unreadable pages and marked "Unreadable: check by hand", before the readable pages go through Find and Review as usual. Or boxes in Review itself (a larger change to a frozen area)?
4. **Remove this page:** recommended: leave out of V1. Otherwise it needs a setting, and the final check must accept a smaller page count.
5. **Which failures count as an unreadable page?** Recommended: only "no readable text" (`LV-PRC-002`), "low confidence" (`LV-PRC-003`) and "page can't be drawn" (`LV-PRC-006`). A timeout or OCR not available (`LV-PRC-004`, `005`) stays a technical stop for IT.
6. Should Text go on after an unreadable page, so all of them are listed at once? Recommended: yes; it changes V1 processing (frozen), so it needs the owner's word.

## Suggested next step
Split it: 0066a (BL1, after answers 3 to 6) and 0066b (reject with reasons, after answers 1 and 2).

No other product's name or behaviour in code.
