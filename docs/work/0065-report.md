# Report 0065: P2b, document summary and case instructions

Date: 2026-11-24
Branch: `feature/0065-summary-instructions`, from `a28ba5e` (0064 merged)
Outcome: **STOPPED** before any product change. The task needs three decisions it does not give.

## What was done
The code was read against the task. Nothing in `backend/` or `frontend/` changed.

## Why it stopped
1. **No finding LexaVault can make needs a party's name.** The proof is "a party named only on page 1 is caught on later pages with the summary, and missed without it". The AI may only answer SSN, date of birth, card number and credit score (`SensitiveContentAnalysisPort.V1_TYPES`, and the answer schema in `OllamaSensitiveContentAnalysisAdapter`). `PERSON_NAME` exists as a name in `EntityType` and nowhere else: it is not in the 0062 catalog, and the FRCP 5.2 pack says "Names of minors are not found". With the four kinds, a summary has almost nothing to add, and "what to protect" in the case instructions cannot be acted on when it is a name.
2. **There is no encrypted store for document text.** The task says "stored encrypted where document text is". Document text is a plain file in the case's processing storage; nothing under `backend/*/src/main` encrypts it. Encryption is 0060 (queue A, READY).
3. **There is no "legal" or "mortgage" policy to switch on.** A policy belongs to an entity and has no kind of business.

Also: public court filings have their SSNs and dates of birth removed before filing (FRCP 5.2), so with today's kinds a real filing would give few or no findings to compare. No filing was downloaded.

## Commands run and results
Searches of the code only. No build, no tests, no model run.

## Questions for the owner
1. **Names.** Recommended: first a task that adds a **Person name** kind (off by default; AI only, with review), and then 0065 on top, so its proof means something. The alternative is to build 0065 with the four kinds and a proof made of numbers only, which would show little.
2. **Encryption.** Recommended: **0065 after 0060**, so the summary and the instructions go straight into the encrypted store. Storing them as document text is stored today would not be what the task says.
3. **Switch.** Recommended: one setting per entity, **off by default**, with the FRCP 5.2 pack suggesting it. Nothing then changes for mortgage entities.
4. **Test documents.** Recommended: synthetic court filings made by the benchmark's generator, next to a few public ones for realism. Agreed?

## Suggested next step
0067, which does not use the summary or the instructions.

No other product's name or behaviour in code (no code changed).
