# Report 0064: Vision-based redaction with page routing

Date: 2026-11-24
Branch: `feature/0064-vision-routing`, from `2a2ebe8`; the integration branch (0057, `4571e89`) is merged into it
Outcome: **DONE** (7 questions, the recommended answer taken in each)

## What was done
1. **Routing, per page, in the Text step** (`PageRouter`, the benchmark's checks moved in unchanged, sharing the step's OCR). Paths: fast, fast with the signature detector, vision. Two additions: a blank page is not "OCR failed", and a check that fails makes the page a hard one. Routes are stored (`document_page_routes`: path, reasons, OCR confidence, signature boxes; no text).
2. **A page OCR cannot read no longer stops the document**: it goes to vision, or to a person. A missing or timed-out OCR engine still stops it, as before.
3. **Vision**: local Ollama only (an address outside this machine or a private network is never asked), `qwen3-vl:8b-instruct`, prompt v2 (its hash is tested), kinds and boxes only. **Pinned by digest**, checked before and after each page; another digest is refused with the new **`LV-AI-008`**, and the page image is not sent.
4. **Never unread.** Vision off, no model, wrong model, time-out, or an answer that is cut short, unreadable or not placeable: the page becomes a "Page to check" finding without a place, which holds the case for a person.
5. **Jobs**: stage `VISION`, one job per routed page, next to `FIND`. The last of both gathers the document, fenced as before. The model is asked outside the transaction, so no database connection is held. Inline mode reads the pages inside its Find step. Documents read before this change are routed when they are searched.
6. **One finding per box**: same place merges, the box covers both, and two paths agreeing raises the confidence by 0.05. A box over 10 times the other's size is never merged.
7. **Every finding records** its path(s) and the AI model, prompt version and digest (text model too). Review shows "Found by". The compliance report has a new section 5 (pages per path; models with digests), printed only when there is something to print.
8. **Settings per entity**: vision on/off (on), routing threshold (0.85), and two new kinds, Signature and Face photo. Admin screen, API, audit and compliance history included. IT: `securedoc.ai.vision.*`, `vision-workers` (2).
9. **Migration 080** (four changes, with rollback), in the 08x range.

## Benchmark through the product (part A, 26 documents, 108 pages, real models, staged)
`test-tools/vision-product/Invoke-VisionProductBenchmark.ps1`. Its scorer gives 0040's published numbers on 0040's saved results.

| | 0040 | Product |
|---|---|---|
| Routed pages, vision alone: found of 130 | 122 | 120 |
| Routed pages, all paths | 122 | **122** |
| False flags, routed pages | 6 | 7 (0.26 per page; the mark is 0.25) |
| All pages, all kinds: found of 274 | 243 | **264** |
| V1 four kinds, misses of 97 | 0 | 2, see below |
| Pages that need vision, routed to it | 16/16 | 16/16 |
| Plain typed pages wrongly routed | 3/16 | 3/16 |
| Signatures | 93/93 | 93/93 |

- Vision findings without model and digest: 0. Unread pages: 0. The poor scan that stopped the case is now read.
- **The two V1 misses** are credit scores on typed pages that vision never sees. The text model answered differently in that run; a second run found both (32 of 32 in the four documents repeated). 0049 reported the same variation.
- **Two handwritten "misses" and some false flags are labels**: the text rule and vision named different kinds at one place, the text rule's kind was kept, and the box covers the value.
- The 21 more found are all on typed pages (23 more from the R1 patterns built since 0040, 2 fewer credit scores), not from vision. Misses left besides these: 6 check-line numbers, on 2 checks and 1 fax.

**Time, 60-page package** (warm): **101.7 s** with vision (5 routed pages), **66.4 s** with vision off. **Not a fair number**: three sessions were using the laptop (0049 measured 35 s without vision on a quiet machine), and the two figures are one run each. Vision pages took 17 to 21 s on average **including the wait for a place** at the AI server, whose limit of 2 is shared with the text model. The 60 s target is **not shown to be met**; it needs a quiet machine (milestone or P3).

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL tests on, merged code | domain 80, application 310, scan bridge 136, infrastructure 121, API 780 (1 skipped, the timing test): **2 failures**, both 0057's completeness checks wanting LV-AI-008's "what to do" text. Added; those classes and all vision classes re-run: 0 failures. The whole suite was not run again |
| `Invoke-VisionProductBenchmark.ps1` (full; then 4 documents, vision on and off) | above |
| `Invoke-TwoServerCheck.ps1` (extended) | run 3, merged code: **PASS, 28 checks** (A killed holding Text, Find, Vision and Redact claims; 40 routed pages, each read, recorded and redacted once) |
| the same, runs 1 and 2 | run 1 found no moment with all four claims (the script's AI limit; changed). **Run 2 FAILED 2 of 28**: one export job at NEEDS_IT, see question 6 |
| `Invoke-ComplianceReportCheck.ps1` (7 checks added) | PASS |
| `npm run test:standalone`, 1 run | auth 10, vocabulary 193, ui-framework 17, oidc-signin 171, tablet 95 pass; a11y 155 pass, **1 failed**: I merged the integration branch while it ran, so an old test met the new licensing screen. On the merged code: auth 10, vocabulary 199, ui-framework 17 pass; a11y 160 and the two changed specs (34) pass. oidc-signin as a whole and tablet were not run again after the merge (CI does) |
| Product-name guard | clean (1731 files) |

**Left for the milestone (rule 9):** the export end-to-end, the no-redacted-value script (the Java check and the two-server database dump ran: 0 matches), the full set on one head.

Existing tests changed: Liquibase rollback counts (+1), counts of kinds (6 to 8, 10 to 12) and codes (+1), and the opt-in pattern test leaves out the two kinds that are not patterns. None weakened. `OllamaInferencePoolTest` failed once under load and passed in both later runs.

## Not done, and why
- **A value vision finds is not looked for on other pages**: it returns no value, by design. The requirement's "wherever it appears in the case" cannot be met that way.
- **One mark per "Page to check"**: Review has one box per finding. Several values on an unread page need one mark over all of them.
- **The text model's digest is recorded, not pinned.** The task pins the vision model; pinning text would change the start of every installation.
- **Neighbouring pages' text** is not given to vision (not in the task or the benchmark).
- `tools/vision-benchmark` was not rebuilt; it still has its own router.
- I ran `mvn install` three times at the start, which writes this branch's jars into the Maven repository the three sessions share. Stopped when noticed; reactor builds do not read them.

## Questions for the owner
1. **Vision-only findings go to a person** (confidence 0.75, below the default 0.80), following 0040's answer 2. So nearly every case with a routed page is reviewed. Keep? (taken)
2. **Signature and Face photo are off by default**, like every new kind. The task says found signatures "are redacted"; switching that on by itself would black out the signatures in every existing customer's output. Off until a Business Admin switches it on? (taken)
3. **Without a vision model, hard pages of every entity go to a person** after the upgrade, as the requirement says. Keep? (taken)
4. **False flags 0.26 per page against the 0.25 mark**: accept until part B? (recommended)
5. **Handwriting check sends 3 of 16 plain scans to vision**, as in 0033. Leave until part B? (recommended)
6. **Existing, not from this task:** a server killed after it wrote a case's export folder and before it recorded it leaves the export at NEEDS_IT (`LV-SYS-001`): the retry refuses the folder that is there (`LocalFolderDeliveryAdapter`, line 100). Its own small task? (recommended)
7. **Pin the text model by digest** too? Its own task. (recommended)

## For the owner
Nothing to do: the model and its digest are installed. Migration 080 runs at the next schema step.

## Files and commits
101 files, +4272 / -184. `af390dc` product, tests, migration; a test commit; the merge `e5d7c96`; the LV-AI-008 text; this report. Pictures in `docs/work/0064-proof/`.

## Suggested next step
0065 (document summary and case instructions).

No other product's name or behaviour in code.
