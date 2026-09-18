LexaVault V1 — EIN vs SSN Precision Fix

Scope
-----
This candidate addresses a false-positive redaction observed on contract 00026-2600453,
page 29 (Vermont Land Gains Withholding Tax Return). The corporate transferor Federal ID
Number was being treated as an SSN because the same tax-form cell also contains the alternate
label "Social Security Number".

Behavior
--------
- A 9-digit value rendered in the canonical EIN 2-7 form (NN-NNNNNNN) is never accepted as SSN.
- Federal ID / Employer Identification / EIN / FEIN / business tax ID / taxpayer ID labels are
  treated as competing non-person tax-ID context for AI-proposed SSN candidates.
- When a dual-purpose field contains both Federal ID and Social Security Number labels, the
  candidate is bound to the closer semantic label.
- A genuine SSN in 3-2-4 form remains accepted when it is actually associated with the SSN label.
- Deterministic SSN discovery remains context-bound; the change does not introduce EIN redaction.

Evidence / versioning
---------------------
- local-rule-based evidence version: 1.6.0
- new safe rejection reasons include SSN_EIN_SHAPE and SSN_NON_PERSON_TAX_ID_CONTEXT
- no database migration; Liquibase remains 54 changeSets
- no change to PDF burn-flat logic, OCR guard, Manual Review UI, Document Intelligence, roles,
  retention, export semantics, or frontend.

Validation note
---------------
Maven is not available in the ChatGPT execution container. The modified Java sources compile in a
standalone Java 21 harness, and targeted harness cases pass for both the observed EIN false positive
and true SSN controls. Run Build-LexaVault-All.ps1 locally for the authoritative full regression.
