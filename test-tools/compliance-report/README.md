# Compliance report check (task 0017)

`Invoke-ComplianceReportCheck.ps1` proves the compliance report end to end on a **throwaway** Postgres
container (`lexavault-0017-pg`, 127.0.0.1:55417). It never touches `securedoc-postgres` or any other
container named in `CLAUDE.md`, and removes only its own container at the end.

```powershell
cd backend; mvn -o clean install; cd ..
.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1
```

What it does:

- seeds the local demo entity through the API: sensitive-data policy changes (and a save with no change),
  a lifecycle change (and a save with no change), 6 cases whose business identifier values and file name
  hold markers, a Reports CSV export and an audit CSV export;
- moves those records 35 days back (SQL, throwaway database only), then seeds deliveries, purges, review
  facts and audit rows in that past period, including audit details holding a file-name and a loan-number marker;
- creates both editions through `POST /api/v1/compliance-reports` as each role: 2 allowed, 10 refused (403, each audited);
- checks `X-Report-File-SHA256` against the bytes and the `COMPLIANCE_REPORT_CREATED` record, and the printed
  fingerprint against that record;
- extracts the PDF text (`ExtractPdfText.java`, PDFBox) and checks the numbers against the seed and
  `GET /api/v1/operations/reports`, one appendix row per record, and that no marker or fake value appears in
  either PDF, the report audit records or the log lines written meanwhile.

One PDF of each edition is saved under `output/` (git-ignored). Exit code 0 only when every check passes.
Evidence (`summary.json`, app logs, extracted text) stays in `%TEMP%\lexavault-0017\<timestamp>`. The container
password is generated per run and never printed.
