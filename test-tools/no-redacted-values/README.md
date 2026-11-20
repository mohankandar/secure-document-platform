# No redacted value is saved (task 0015)

End-to-end check that a known fake value never reaches the database, the app logs or the audit
export, in full, as digits only, or as its last 4.

```powershell
cd backend; mvn clean install; cd ..
.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1
```

What it does:

1. Starts a throwaway Postgres container `lexavault-0015-pg` on `127.0.0.1:55415`. It never
   touches `securedoc-postgres` or the other containers listed in `CLAUDE.md`, and removes only
   its own container at the end. The password is generated per run and not printed.
2. Starts `Start-FakeValueOllama.ps1` on `127.0.0.1:11499`: a mock Ollama whose answer names the
   fake values, so the AI path carries them.
3. Starts the built backend jar (LOCAL profile) on port 18015 against that database. Liquibase
   runs every migration, including 039.
4. Dumps the database, processes a PDF made by `MakeFakeValuePdf.java` (PDFBox from the local
   Maven repository), dumps again, exports the audit log (JSON and CSV), stops the app.
5. Searches the second dump (only matches new since the first dump count), every log file and
   both audit exports. Digit forms count only when not part of a longer hex or digit run.

Fake values: SSN `412-55-7531`, card `4000 0566 5566 5556`, date of birth `03/14/1962`.

Evidence (dumps, logs, exports, `summary.json`) stays in `%TEMP%\lexavault-0015\<time>` unless
`-RunRoot` is given. It holds fake test values only. Exit code 0 means PASS.

The in-JVM half of the same check is `NoRedactedValueSavedTest` in `securedoc-api`.
