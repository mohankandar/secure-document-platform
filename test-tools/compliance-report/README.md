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

Task 0018 adds: the AUTHORIZATION_FAILED record of a refused export (capability, method, route pattern only; the
query string is never recorded), the lifecycle `previous=` values and the section 2 changes table, no "Rejected" rows,
the note under section 3, single spaces in the footer fingerprint, and the licence's customer name. For that last one,
`MakeTestLicence.java` signs a throwaway test licence with a key pair made for the run (the private key is never
written); the app trusts its public key through the LOCAL profile's development-key override, and the token file is
deleted once installed. The vendor key is never used.

One PDF of each edition is saved under `output/` (git-ignored), and one of each with the test licence installed
(`*-licensed-sample.pdf`). Exit code 0 only when every check passes.
Evidence (`summary.json`, app logs, extracted text) stays in `%TEMP%\lexavault-0017\<timestamp>`. The container
password is generated per run and never printed.
