# Task U012: Support, problem detail and Continue manually (findings 9, 10, 30)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Designs: `designs/Support.html`, `designs/ProblemDetail.html`, `designs/ContinueManually.html`.

## Ask first

These are legacy pages (`SupportIncidentsPage`, `SupportIncidentDetailPage`, `TechnicalIssueDetailPage`, `ManualFallbackPage`) covered by the UI framework contract test (`PageScaffold`). Moving them into the Case File look is a change in a frozen area. Before building, write in the report which contract rules would change and how, and stop for the owner's yes.

## After the owner's yes

1. **Support list:** Case File rows: case (primary identifier and name), what happened in plain words with the attempt count, impact chip (Case stopped, Continued manually, Case finished), status (Open, Watching, Resolved), last seen, Open. Filter chips Open / Resolved / All and one search box; other filters under More filters.
2. **Problem detail:** one page replacing Incident Details and Technical Issue Details: title in plain words, stage chip, three blocks (What happened; What it means for the case, with the keep-until date; What you can do: Retry for IT Administrator, "Keep the original 7 more days"), a short timeline, and every code and id under Technical details.
3. **Continue manually:** Case File look and words; readable in dark (today several labels are dark on dark); breadcrumb "Cases › <id> · <name>"; type names from the vocabulary; a normal-size confirmation checkbox; finish button "Finish and send to approval".
4. Map reason codes to plain sentences in the U001 vocabulary module (OCR_TIMEOUT → "Text recognition timed out", SDV_UNAVAILABLE → "Secure Document Vault didn't answer", MODEL_TIMEOUT → "AI model didn't answer in time"); unknown codes show "Processing problem" plus the code under Technical details.

## Roles

Support and problem detail: IT Administrator and Platform Operations; Retry: IT Administrator only. Continue manually: Document Processor and Supervisor.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
