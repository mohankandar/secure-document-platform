# LexaVault Business / Operational Reporting R1 — FIX1

## Fix
Corrects the PostgreSQL syntax error in the reporting review-handling-time query:

`ERROR: syntax error at or near "review_handling_hours"`

The native SQL expression for `review_handling_hours` was missing the closing parenthesis for `extract(...)`.

## Scope
- One source correction in `JpaOperationsReadAdapter`.
- No schema/Liquibase changes.
- No reporting semantics changed.
- No AI, scanner, redaction, OCR, identity, or retention changes.

## Local verification
Run:

```powershell
.\build-all.cmd
.\start-local.cmd -LoggingFormat Readable
```

Then open Reporting and confirm the report API renders without `SQLGrammarException` near `review_handling_hours`.
