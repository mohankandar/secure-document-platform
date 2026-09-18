# LexaVault V1 - Manual Review Precision Regression Fix - Local Validation

## 1. Build and baseline

1. Keep **Document Intelligence = DISABLED** for this stability validation.
2. Run `build-all.cmd` (or the normal full `Build-LexaVault-All.ps1`).
3. Expected: backend and frontend builds/tests succeed.
4. Start the normal `local` profile and confirm health is green.
5. Confirm Liquibase remains **54**:

```sql
select count(*) from databasechangelog;
```

No migration belongs to this fix.

## 2. Primary regression - exact synthetic reference PDF

Process the exact `000422610006.pdf` source again as a **new intake**. The business/contract
identifier may differ for the new run; the PDF content is the regression fixture.

Expected behavior:

- Pages **2, 5, 6, 7, 8, 10, and 14** must not return to Manual Review merely because they
  mention sensitive categories or ordinary financial/administrative values.
- Page **15** already contains permanent source blackouts for SSN, DOB, payment-card number,
  and credit score. Those blackouts must be recognized as already protected and must not create
  `Location needs confirmation` review items.
- The genuinely exposed secondary-owner DOB on page **3** must still be found and permanently
  redacted by the existing baseline path.
- If there is no other legitimate unresolved finding, the package should proceed without
  Manual Review.

## 3. Expected log evidence

Revision-1 runtime showed:

- `ocrGuardReview=3`
- final page-15 review findings: `SSN`, `DATE_OF_BIRTH`, `CREDIT_CARD_NUMBER`
- AI page-15 card/DOB/score candidates were rejected as implausible; the three persisted items
  came from the OCR guard.

After Revision 2, expected for the same source:

- `ocrGuardReview=0` for the pre-masked/category-only controls
- final findings should still include the real located page-3 DOB
- no page-15 unlocated SSN/DOB/PAN/Credit Score findings solely because source values are blacked out
- detector evidence for any legitimate new OCR-guard review finding should report version `1.4.0`

## 4. Redacted-output safety

Open the resulting redacted/exported PDF and compare against the original:

- page 3 exposed secondary DOB is permanently blacked out
- existing source black boxes on pages 3/4/5/9/10/15 remain black
- page 6 generic privacy/category wording stays visible
- page 7 contract amounts/APR/dates stay visible
- page 8 financing amounts/APR/payment count stay visible
- page 14 policy/category wording stays visible
- no unrelated over-redaction is introduced

## 5. Fail-safe boundary

This fix must preserve the conservative side of the OCR safety guard:

- exact visible credit score -> locate/redact path remains
- score-shaped OCR ambiguity such as `76B` -> Manual Review remains
- strong blank sensitive label **without** an aligned verified blackout -> Manual Review remains
- normal high-confidence unreadable payment-card value -> Manual Review remains
- a visible sensitive value must not be suppressed by an unrelated dark region elsewhere
- only category prose and evidence-backed pre-existing source blackouts are suppressed

## 6. Four-document no-regression spot check

The other four PDFs previously behaved correctly. A full re-run is optional before Kiro, but
at minimum do not change their expected behavior:

- actual exposed credit scores continue to be found/redacted
- generic privacy wording remains non-finding content
- masked card values do not create review noise
- no new Manual Review explosion

## 7. Kiro checkpoint

Only after local build and the exact synthetic PDF runtime check pass should Kiro verify this
narrow unit. Kiro should verify and report; it should not implement/refactor unless explicitly asked.
