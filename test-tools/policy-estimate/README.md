# Policy estimate check (task 0019)

`Invoke-PolicyEstimateCheck.ps1` proves `GET /api/v1/business-admin/sensitive-data-policy/estimate` on a
**throwaway** Postgres container (`lexavault-0019-pg`, 127.0.0.1:55419). It never touches `securedoc-postgres` or any
other container named in `CLAUDE.md`, and removes only its own container and processes at the end.

```powershell
cd backend; mvn -o clean install; cd ..
.\test-tools\policy-estimate\Invoke-PolicyEstimateCheck.ps1
```

What it does:

- starts the task 0015 mock Ollama and the backend jar (LOCAL profile), and saves the policy `UNCERTAIN_ONLY` at 0.99, so the mock's findings (0.92-0.97) wait for a person;
- sends 21 real cases through intake and processing: 9 PDFs with the mock's fake values (found and located),
  6 with other text (the mock's values are not on the page: no findings kept), 6 blank (no findings). Run on
  2026-11-20: the 9 fake-value cases waited for review (27 findings at 0.92-0.97), the 12 others were delivered.
  Nobody reviews them, so the case statuses and the findings still `DETECTED` are the real outcome under the policy;
- checks the estimate for the saved policy against those real outcomes, `REVIEW_ALL` and 0.80 against independent
  SQL, validation (400), every other role (403), that reading it writes no audit record, and that the response holds
  no found value, file name or business identifier;
- seeds a second entity by SQL with 2,000 cases and 50,000 findings in the last 30 days (2 documents per case, about
  10% unlocated, 2% manual) plus 200 older cases, checks the first entity's estimate does not change, times five calls
  for the second entity (each under 1 second), and saves `EXPLAIN (ANALYZE, BUFFERS)` of the adapter's own SQL, read
  from `JpaPolicyEstimateReadAdapter.java`.

Evidence (`summary.json`, `estimate-query-plan.txt`, app logs) stays in `%TEMP%\lexavault-0019\<timestamp>`. The
container password is generated per run and never printed. Exit code 0 only when every check passes.

`MakeEstimatePdf.java` writes the `plain` and `blank` PDFs (PDFBox on the class path).

**Task 0020 A4 (the U023 confirm step against real data).** `-KeepRunning` leaves the container, the mock Ollama and the
backend running after the checks (process ids in `%TEMP%\lexavault-0019\keep-running.json`). Then:

```powershell
cd frontend\securedoc-ui
$env:VITE_SECURITY_MODE='LOCAL'; $env:VITE_CASE_FILE_UI_ENABLED='true'
node node_modules\vite\bin\vite.js --config ..\..\test-tools\policy-estimate\vite.realdata.config.mjs   # UI on 15180
node ..\..\test-tools\policy-estimate\Invoke-ConfirmStepRealData.mjs ..\..\docs\work\ui\compare\U023
..\..\test-tools\policy-estimate\Stop-PolicyEstimateEnvironment.ps1   # removes lexavault-0019-pg only
```

`Invoke-ConfirmStepRealData.mjs` acts as the Business Administrator, chooses Light from the saved Custom 0.99, checks the
confirm step's numbers against the estimate the server sent, presses Keep (nothing saved), then Save (saved), and writes
`realdata-*.png` (git-ignored). The test UI's proxy drops the browser's Origin header, because the backend allows only
its configured origins and port 15180 is not one.
