# LexaVault test strategy

**Status:** proposed by the LexaVault design chat, 2026-11-22, for the owner's approval.
**Owner's approval:** _not yet_
**Why:** the 0031 live run showed every test passing while LexaVault couldn't actually talk to Vero: the tests used fakes copied from our own contract. This plan makes sure each kind of risk is tested by the right kind of test, against the real thing where it matters.

## 1. Principles

1. **Test against the other side's truth, not our own copy.** A destination is tested against its published, pinned specification, never against a fake we wrote from our own document.
2. **Fake data only.** Tests never use real customer documents. The one exception is the vision benchmark (part B), with the customer's approval, numbers-only report.
3. **The safety checks run on every change:** no redacted value ever saved, the compliance report still correct. They already exist; they stay mandatory.
4. **Every test layer has an owner and a time to run.** Fast tests on every build; slow or live tests on demand, marked so they can't be forgotten or run by accident.
5. **A task isn't done on unit tests alone** when it touches a boundary (another system, the database, the browser): it needs the layer that reaches that boundary.

## 2. The layers

| # | Layer | What it proves | Where it lives | When it runs |
|---|---|---|---|---|
| L1 | **Unit and component tests** | Each rule and class behaves (retry schedule, codes, mapping) | LexaVault repo (today ~1,050 backend, ~700 frontend) | Every build |
| L2 | **Database tests** | SQL, migrations, locking (two workers, one job) on a real PostgreSQL | LexaVault repo, throwaway container | Every task that touches SQL; on demand otherwise |
| L3 | **Contract tests** | What LexaVault sends matches what the destination publishes (addresses, headers, fields, sign-in, answers) | platform-core (integration module), against the **pinned** Vero spec `sdv-ingestion-v1.0.0`; one per published API | Every platform-core build |
| L4 | **Mock destination server** | The whole app's behaviour when a destination is working, down, slow, busy (429), refusing with a code, already has it | platform-core test kit, **generated from the pinned spec**, with switches; also a mock folder/share and a mock source API for intake | LexaVault integration tests (L5) and manual checks |
| L5 | **Integration tests (whole app)** | LexaVault running for real: intake → processing → review → export, with L4 in place of other systems | LexaVault repo, marked **integration**, skipped in normal builds | Before each merge of a boundary task; nightly once a build machine exists |
| L6 | **Screen tests** | Screens, words, accessibility (axe light and dark), tablet | LexaVault repo (Playwright suites, existing) | Every build |
| L7 | **Safety checks** | No redacted value in database, logs, audit; compliance report correct | LexaVault test tools (existing) | Every task |
| L8 | **Live end-to-end** | LexaVault and the **real** Vero (and later a real customer destination) together | Joint run, both apps on the owner's machine | Before a release; after either side changes its API |
| L9 | **Performance** | Throughput, worker scaling, AI limit learning, the 60-seconds-per-document target | LexaVault (`Invoke-LexaVault-PerformanceBaseline.ps1`), the vision benchmark (0033) | With P3, vision, and before a release |
| L10 | **Security** | Pen test, dependency checks, secret scanning | Existing dependency check; pen test and SOC 2 readiness in the backlog (BL2) | Before the first customer; then yearly |

## 3. The mock destination server (L4), in detail

- **Built from the pinned Vero specification**, so it can't drift from the real Vero. When Vero releases a new spec, the pin moves and the mock moves with it.
- **Switches** (set per test, or from a small admin call when clicking through by hand):
  working · down (connection refused) · slow (answers after N seconds) · busy (429 with Retry-After) · temporarily unavailable (503) · refuses with a code (for example `SDV-SEN-006`, `SDV-INT-011`) · already received (200 replay, `DUPLICATE_ALREADY_STORED`) · sign-in refused (`SDV-AUT-*`) · health check failing while uploads work, and the reverse ("answering but failing").
- **Records** what it received (headers, fields, checksums; never logs document text) so tests can check exactly what LexaVault sent.
- **Also:** a mock folder/share (can be made unwritable or slow) and a mock source API for polling intake.
- **Owner:** platform-core builds it with the integration module (P021/P022). LexaVault uses it from switch-over part 3.

## 4. What we can test now (before the module exists)

- **Task 0035: end-to-end run of the export job against the folder destination** (the one destination that works today). With the real app running: make the folder unwritable (down), check the pause, the checks every 5 minutes, the resume in order, the IT queue and every export screen; a refusal case via a file the folder rejects; clean-up afterwards. Marked integration (L5), with proof pictures. This proves the export job, retries and screens for real, everything except the Vero-specific part.
- After that, every boundary task adds its L5 test the same way.

## 5. Gates

| Gate | Must pass |
|---|---|
| **Every task** | L1, L6, L7; L2 if it touches SQL; L5 if it touches another system or the running app |
| **Merge into the integration branch** | The task's gate, plus the design chat's review of the report and proof pictures |
| **Before a push / release candidate** | Everything above, plus L5 in full, L8 (live with Vero), L9 baseline unchanged or better, and the **AI model change gate** (owner, 2026-11-22: any change to a model, its digest, a prompt or an AI setting re-runs the model test set and must meet the approved pass marks; the result is recorded for the model register, `Model-Governance-Design.md`) |
| **Before the first customer** | All of the above, plus L10 (pen test), the install checklist (`First-Install-Success-Criteria.md`), and the vision benchmark part B on their samples |

**The AI model change gate, how it is measured (task 0080, 2026-11-24):**
- The product benchmark (`test-tools/vision-product`) is run three times on the approved state; a change is compared against the **lowest** of the three. Run-to-run spread measured on 2026-11-24: none on routed pages, one item of 274 overall.
- **The AI server's own version counts**: an update of it is a model change, also with the same model digest. Record it with the result. (0064: 122 of 130 on Ollama 0.40.17; the same commit and digests: 119 of 130, three times, on 0.40.18.)
- Baseline since 2026-11-24 (Ollama 0.40.18, `qwen3.5:9b`, `qwen3-vl:8b-instruct`, names off): **119 of 130** on routed pages, **262 of 274** overall, at most 2 V1 misses, at most 8 false flags, no unread page.
- Since task 0091 (2026-11-25, fixed order of the answer schemas; proposed in its report): **263 of 274** overall and at most 1 V1 miss, the same in three runs; the rest as above.
- Since task 0092 (2026-11-25, check lines; proposed in its report): **127 of 130** on routed pages, **271 of 274** overall, at most 1 V1 miss, at most 4 false flags, no unread page.
- Person names (task 0072): at least **85%** found and at most **1 false name per 10 pages**, on documents the prompt was not tuned on.

## 6. Where the work goes in the order

| When | Testing work |
|---|---|
| Now | **0035** export end-to-end against the folder destination |
| platform-core P021/P022 | Contract tests against the pinned Vero spec; the mock destination server; mock folder and mock source API |
| Switch-over part 3 | LexaVault's L5 integration tests move onto the mock server |
| After Vero 0040 and part 3 | **L8 live run with Vero** (0031 re-run) |
| P3 | L9 performance proof (400 / 4,000 / 40,000 documents) |
| Vision | Benchmark parts A and B (0033) as its gate |
| Before the first customer | L10 pen test; the release gate in full |
