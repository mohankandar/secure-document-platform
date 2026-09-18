# LexaVault V1 — EIN vs SSN Precision Fix Local Checklist

## 1. Full build
Run `Build-LexaVault-All.ps1` (or `build-all.cmd`). Expect all backend modules, frontend build, and auth tests to pass.

## 2. Exact regression case
Process the original `000262600453_ORI.pdf` as a **new intake** with Document Intelligence `DISABLED`.

Expected:
- Page 15: the actual consumer credit score remains permanently redacted.
- Page 29: corporate transferor Federal ID/EIN `NN-NNNNNNN` is **not** classified/redacted as SSN.
- Page 29 buyer-side Social Security Number field is blank and remains untouched.
- Package completes without a new Manual Review item for the corporate EIN.
- Selective PDF artifact behavior remains intact: untouched pages remain native/searchable; only pages with real redactions are rasterized.
- Output size remains near the original rather than expanding 4–7x.

## 3. Targeted regression expectations
Confirm tests cover:
- corporate EIN beside `Federal ID Number` + nearby `Social Security Number` => reject SSN
- EIN shape even when OCR drops the Federal-ID label but keeps SSN wording => reject SSN
- genuine `123-45-6789` in `Social Security Number` context => still accept
- genuine SSN in dual-purpose `Federal ID Number OR Social Security Number` field => still accept
- existing SSN business-reference precision tests remain green
- existing R11 DOB/card/credit-score precision suites remain green

## 4. Logs
For the page-29 AI candidate, if the model still proposes the corporate EIN, expect safe rejection evidence such as:
- `AI_FINDING_REJECTED type=SSN ... reason=SSN_EIN_SHAPE`
No raw EIN, OCR text, or surrounding source text should be logged.

## 5. Baselines
- Liquibase: 54 changeSets
- Document Intelligence behavior unchanged
- Permanent-redaction implementation unchanged
- PDF Artifact Quality selective-page implementation unchanged
