# Final V1 Pre-Acceptance — Focused Credit-Card Precision Fix

**Date:** 2026-08-31  
**Status:** Implemented by ChatGPT; user runtime rerun pending  
**Scope:** Credit-card precision only

## Trigger

The same 61-page customer package completed real Ollama analysis after the prior retry/DOB/SSN fixes. Final findings were:

- `CREDIT_SCORE=1` — expected, page 59;
- `CREDIT_CARD_NUMBER=2` — false positives, pages 24 and 31;
- `DATE_OF_BIRTH=0`;
- `SSN=0`.

Pages 24 and 31 contain repeated 13-digit barcode/control markers and no exposed payment-card PAN.

## Narrow correction

- Tighten `CREDIT_CARD_NUMBER` model instructions to actual exposed PAN values only.
- Require plausible PAN shape before accepting an AI card result: 13–19 digits, normal spaces/hyphens only.
- Reject repeated single-digit control markers such as `7777777777777`.
- Stop aliasing generic `FINANCIAL_ACCOUNT` model output into `CREDIT_CARD_NUMBER`.
- Preserve deterministic Luhn validation as secondary evidence rather than an AI-discovery gate.
- Add safe rejection diagnostics with page/reason/digit count only.
- Add focused `CreditCardPrecisionTest`.

## Explicitly deferred

Do **not** perform the provider-neutral / tenant-prompt refactor yet. Evaluate this focused precision rerun first, then return to the previously discussed production AI-provider/prompt architecture work.
