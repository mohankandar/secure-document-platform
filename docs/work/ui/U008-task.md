# Task U008: Admin › Integrations and the connection editor (findings 16, 24, 25)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Designs: `designs/AdminIntegrations.html`, `designs/ConnectionEditor.html`.

## List

1. View names: **Receiving**, **Sending to SDV**, **Sending by barcode**, All (were Intake sources, Master delivery, Derived delivery).
2. Each connection: name, one tag saying what it does ("Sends to LexaVault", "LexaVault collects every 15 min", "Folder", "File share"), and one plain status line ("Working · last received 14 min ago", "Slow · last try failed: no answer in 30 s", "Switched off"). Address in small mono text. Authentication type, secret, configuration version and limits go under Technical details.
3. Buttons: Collect now (polling), Check now (watched folder), Switch on/off, Edit. "Technical retry" → **Retry**.
4. Watched folder appears here once, as a Receiving connection with Check now (finding 16).
5. Sending by barcode: the rules read as a sentence ("Title pages: barcode starting 'TITLE-', top right of the page → Title team folder"), and failed files name the case by its primary identifier, not the package id.

## Connection editor

6. Three steps: (1) what: Receive or Send, as two option cards with one-line descriptions; (2) details; (3) test and save.
7. Plain labels with the technical meaning in help text: "How documents arrive", "Address", "How LexaVault proves who it is" (authentication), "Where the key is stored" (secret reference; the key itself is never shown), "Collect every".
8. Timeouts, retries, incomplete window, file limits and acknowledgement under a collapsed **"Advanced (usually leave as is)"**.
9. **Test connection before saving.** Backend needed if testing an unsaved draft is not possible today: propose the endpoint in the report and ask first.

## Roles

IT Administrator only (unchanged).

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.
