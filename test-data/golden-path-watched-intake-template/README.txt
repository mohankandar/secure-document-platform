LexaVault V1 Fresh Golden-Path Watched-Folder Intake Template

These PDFs are synthetic and contain no real customer data.

DO NOT copy this template directory wholesale into backend/watched-intake while the watcher is active.
Use Stage-GoldenPath-WatchedIntake.ps1 from the project root. The script stages each complete
candidate outside the watched root and moves it into place only after the PDF and manifest are ready.

LOCAL defaults in this R4 candidate:
- watched-folder enabled: true
- automatic scheduler enabled: true
- scan interval: 10 seconds
- watched root: backend/watched-intake
- accepted + duplicate terminal folders: moved to backend/watched-intake/_archive
- rejected terminal folders: moved to backend/watched-intake/_problem

Cases:
01-clean           -> no sensitive findings; should complete/export cleanly
02-auto-redaction  -> score 742 should be permanently redacted
03-manual-review   -> ambiguous score 76B should require legitimate Manual Review
04-approval        -> synthetic SSN redaction + final approval role checks
05-reprocess       -> DOB redaction on cycle 1; then invoke Reprocess on SAME package
06-duplicate-a/b   -> exact same fingerprint; A accepted, B DUPLICATE_INTAKE
07-export          -> synthetic full PAN redacted; delivery/export status reconciles

Keep Document Intelligence DISABLED for this matrix.
