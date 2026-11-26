# The owner's test files through the product

Task 0099. Runs the synthetic test files (by default `C:\dev\lexavault\test-pdfs`, with its `long` folder) through the built LexaVault by the watched folder. Each delivered file is then read again, both its text layer and OCR of every page, to check that no protected value is left in it.

## Run it

1. Docker Desktop is running, and Tesseract is on the PATH.
2. Build once, from the repository root: `cd backend; mvn -Pdev-build clean install`.
3. From the repository root:

```
node test-tools\owner-files\owner-files.mjs [folder of test PDFs]
```

It takes about 10 minutes. It does not touch your local LexaVault, its database or its folders. It starts its own database (`lexavault-0099-pg`), the app, and a stand-in AI that finds nothing. Only the pattern rules and OCR find anything, which is the hardest case for them. It stops all of them at the end.

## What it prints

- One line per file: delivered, refused at intake (the password-protected file), or not delivered with the case status. When a case was held in Review, it also lists what it was held for. The run confirms the value items as a reviewer would ("redact"). A page to check is left for a person, and that case stays held.
- `No value in the database or the logs`: PASS when none of the synthetic values is stored or logged.
- One line per delivered file from `CheckDelivered.java`: `CLEAN`, or `LEAK` with the kinds and where they were seen (`text` or `ocr`). It never prints a value. The last line is **ALL CLEAN**, or how many files still hold a value.

The values it looks for are the ones in the test files' `README.md` and `long\*-expected.txt` that are on by default for a new entity: SSN, date of birth, card number, card security code and credit score.
