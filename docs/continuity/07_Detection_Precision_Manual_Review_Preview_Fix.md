# Detection Precision + Manual Review Preview Fix — 2026-08-31

Status: ChatGPT implementation/self-review complete; authoritative user build/runtime verification pending.

## Triggering runtime evidence

A 61-page native-text customer PDF completed AI analysis successfully after the page-retry correction, but detection reported `CREDIT_SCORE=1, DATE_OF_BIRTH=27, SSN=1`. Inspection of the source sample established that the credit score on page 59 was expected, while many ordinary dates and a privacy-policy mention of `Social Security number` were false positives. The reviewer workbench also showed a blank preview with generic `HTTP 400: Bad Request`.

## Narrow source changes

- contextual deterministic DOB discovery instead of generic date-shape discovery;
- DOB validator treats a structurally valid date without birth context as not-applicable evidence;
- stronger Ollama semantic instructions for DOB/SSN/credit-card negative cases;
- semantic SSN output sanitation requires an exposed numeric value;
- safe semantic/deterministic evidence-count logging;
- safe Manual Review preview rejection/render logs;
- safe validation-path/correlation logging;
- frontend preview accepts PNG or JSON error responses and surfaces the safe backend message;
- focused DOB precision tests.

## Non-goals

No new sensitive category, no broad review redesign, no weakening of tenant isolation/status/original-access rules, no model change, no queue/microservice redesign.

## Verification required

Run `build-all.cmd`, start LOCAL, rerun the same customer package, and capture logs from `PACKAGE_PROCESSING_STARTED` through final state plus any `REVIEW_PREVIEW_*` line when opening Manual Review.
