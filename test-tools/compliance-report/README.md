# Compliance report check (task 0017)

`Invoke-ComplianceReportCheck.ps1` proves the compliance report end to end on a **throwaway** Postgres
container (`lexavault-0017-pg`, 127.0.0.1:55417). It never touches `securedoc-postgres` or any other
container named in `CLAUDE.md`, and removes only its own container at the end.

```powershell
cd backend; mvn -o clean install -Pdev-build; cd ..
.\test-tools\compliance-report\Invoke-ComplianceReportCheck.ps1
```

What it does:

- seeds the local demo entity through the API: sensitive-data policy changes (and a save with no change),
  a lifecycle change (and a save with no change), 6 cases whose business identifier values and file name
  hold markers, a Reports CSV export and an audit CSV export;
- moves those records 35 days back (SQL, throwaway database only), then seeds deliveries, purges, review
  facts and audit rows in that past period, including audit details holding a file-name and a loan-number marker;
- creates both editions through `POST /api/v1/compliance-reports` as each role: 3 allowed (U022: the IT Administrator may also create the summary), 9 refused (403, each audited); a tenant-boundary 403 recorded with reason=TENANT_BOUNDARY;
- checks `X-Report-File-SHA256` against the bytes and the `COMPLIANCE_REPORT_CREATED` record, and the printed
  fingerprint against that record;
- extracts the PDF text (`ExtractPdfText.java`, PDFBox) and checks the numbers against the seed and
  `GET /api/v1/operations/reports`, one appendix row per record, and that no marker or fake value appears in
  either PDF, the report audit records or the log lines written meanwhile.

Task 0018 adds: the AUTHORIZATION_FAILED record of a refused export (capability, method, route pattern only; the
query string is never recorded), the lifecycle `previous=` values and the section 2 changes table, no "Rejected" rows,
the note under section 3, single spaces in the footer fingerprint, and the customer name of the licence in force
(task 0057: the stored platform licence, here the demo licence).

Two PDFs of each edition are saved under `output/` (git-ignored; the second as `*-licensed-sample.pdf`). Exit code 0 only when every check passes.
Evidence (`summary.json`, app logs, extracted text) stays in `%TEMP%\lexavault-0017\<timestamp>`. The container
password is generated per run and never printed.

Task 0019 adds: `GET /api/v1/audit-records/count` for the same custom period equals the full edition's appendix row
count; a Business Administrator gets 403 for it (recorded once as `AUTHORIZATION_FAILED`); reading it is not audited.

Task 0020 adds "What LexaVault did for you". Minutes per page: the default 1.0 (migration 041); 0.4, 30.5, 1.25 and 0
are refused with 400 and write no record; 30, 0.5 and 2.5 are saved, each with one `REPORTING_CONFIGURATION_CHANGED`
record holding the new value and `previous=[...]`, numbers only; a save with no change writes none. On hand-made facts
(pages and documents per case, findings in each status, one unreviewed case finished by hand, and a second entity's
big case in the same period) the `timeSaved` block of Reports is checked number by number, against Reports' own
"Delivered" and "No review needed"; and section 3 of the PDF prints the new table and its grey line with the minutes per
page as it stood at the period end (2.5), while Reports uses today's setting (1.5).

U024 step 0: Reports' "No review needed" (headline, its target bar, Service levels, and the compliance report's row)
now leaves out cases finished by hand, by the same rule as `casesNoPerson` (`ReportingCaseRules`). On the hand-made
facts "No review needed" is now 1 (25%), not 2 (50%), and the check proves it equals `casesNoPerson` with an
unreviewed case finished by hand in the period.
