# Report 0106: owner's test round in one command, with a scorecard

Date: 2026-11-27
Branch: `feature/0106-owner-run`, from `origin/feature/lexavault-r1b-tenant-identity` at `0a756fc`
Outcome: **DONE** (4 questions)

## What was built
Test tooling only, under `test-tools/owner-run/`. No product change, no new dependency (PDFBox from the build, Tesseract and Ollama already on the machine).

1. **`owner-run.mjs`** (plus `Run-OwnerRound.cmd`). One command:
   - starts a **throwaway** PostgreSQL (`lexavault-0106-pg`, port 55406), runs the install steps, and starts LexaVault LOCAL against it (port 18106) with the real models: text `qwen3.5:9b`, vision `qwen3-vl:8b-instruct` (pinned digest);
   - drops the test set as the 8 folders of `test-pdfs\watched-drops-run2` (the 13 files and `long\*.pdf`). PDFs come from the test set, manifests get **new contract numbers every round**;
   - waits until every case is finished or waiting for a person, then writes **scorecard 1 (`before-review`)** before anything is touched;
   - stops the app and removes the container (also on failure).
   It never touches the owner's containers, database or folders.
2. **The scorecard** (`results/<round>-<label>.md`, plus a console summary). It holds kinds, pages, counts and codes, **never a value**:
   - **leaks** per file and page: a value of a kind that is on (or locked) still readable in the **delivered** file, read three ways: text layer (with form fields and comments), OCR, and the vision model on pages that were images in the original;
   - **extra redaction** per file and page: visible words the page lost, apart from where the expected values stand. Both pages are read by OCR, or by the vision model on image pages, so removed hidden text doesn't count. Words the watermark runs together are not counted, and pages LexaVault holds as unreadable are left out;
   - automatic %, findings, findings sent to a person **with the reason** (0105's `person_reason`, else Review's reason), findings without a place;
   - intake per drop (taken, refused with `LV-` code, waiting), unread pages, time per case.
   Expected answers: `expected.json` (files 01–13, from `gen.py` and the README) and `long\*-expected.txt`, read as they are.
3. **Second mode, after the owner's review:** `--review` runs the same round on port 8080 and leaves the app up for the normal screen. `--after` then scores again, lists what changed (per case: status, each finding whose decision changed), and ends the round. `--stop` ends it without scoring.
4. **Unattended stand-in reviewer (added, question 1):** LexaVault makes the redacted copy only after Review, and nobody can download it before. So scorecard 1 can score leaks only for cases that needed no person (2 of 6). After scorecard 1, a plain round therefore **accepts every proposal as it stands**: value findings with a place are redacted, pages to check are marked checked with nothing added, and the case is approved. Then it writes **scorecard 2 (`as-proposed`)**. A finding without a place can't be redacted without a mark (0100), so its case stays held, and the scorecard says which file and page. `--no-accept` skips this step.
5. **`README.md`** for the owner: the command, about 15 minutes, how to read each line.
6. **`C:\dev\lexavault\Update-And-Start.ps1`** (outside the repo):
   - refuses if the checkout is not on the integration branch or has local changes, and lists the files;
   - stops LexaVault, runs `git pull --ff-only`, then `Build-LexaVault-All.ps1 -SkipTests`;
   - starts `securedoc-postgres` if a Docker restart left it stopped (never creates or removes a container);
   - starts LOCAL with `qwen3.5:9b` and `qwen3-vl:8b-instruct`, then opens http://localhost:5173.

## Proof
On today's merged head (`0a756fc`), Ollama 0.40.18. Final code, two full rounds:

| | Round 20261127-183024 | Round 20261127-184508 |
|---|---|---|
| Intake | 6 taken, drop-14 refused LV-INT-008, drop-15 waiting (no manifest) | same |
| Scorecard 1: leaks / extra words / automatic | 0 / 1 / 2 of 6 (33%) | same |
| Scorecard 1: findings / to a person / no place / unread pages | 114 / 13 / 3 / 1 | same |
| Scorecard 2: leaks | **2: `long-60-pages.pdf` p23 and p32, DATE_OF_BIRTH, read by the vision model** (the known handwritten DOBs) | same |
| Scorecard 2: extra words, to a person, held cases | 29, 2, 2 (09 p1 SSN and 06 p1 DOB with no place) | same |

- The two scorecards of each kind are **identical** apart from case ids and seconds (`diff` after masking those). Times per case: 91–119 s, long files 260 s. A round takes about 15 minutes.
- Rounds 2 and 3 (an earlier version of the extra-redaction count) were identical to each other too.
- **`--review` / `--after`**, tried on drop-12 alone. The app stayed up on 8080 after the command ended. I reviewed by the API with choices different from the stand-in: dismissed the low-confidence DOB on file 10 p3 and marked the page checked. `--after` showed the case PENDING_REVIEW → COMPLETED, both decisions, and **a new leak: file 10 p3 DATE_OF_BIRTH (vision model)**, the value the dismissal left. Then it removed the container and freed the port.
- **`Update-And-Start.ps1`**: parses. Its refusal (wrong branch) was tried on my worktree and changed nothing. A second try with the branch check pointed at my own branch ran the stop script (nothing was running) and then stopped at `git pull` (branch not on GitHub). It was **not run on the owner's checkout**: it would stop his app and pull. That is his step.
- A scorecard grep for the test values finds none.
- Builds: `mvn -Pdev-build -DskipTests package` in `backend` (reactor build, **no `mvn install`**, rule 11). No product code changed, so no Maven or frontend tests were run, per rule 9. `node --check` on the script; the Java helper compiles at each run.

**After merging the newest integration branch**, rebuilt each time:
- after 0103 (label-free detection) and 0109 (hardening), head `349383c`: round 20261127-195424 (scorecard not kept, superseded);
- after 0105 (finding places), head `dcf641c`: round 20261127-203343, kept in `results/`.

| Round 20261127-203343 | Scorecard 1 | Scorecard 2 |
|---|---|---|
| Leaks / extra words | 0 / 0 | 0 / 9 (09 p1: 6, 04 p1: 2, 07 p1: 1) |
| Automatic | 1 of 6 (17%) | (kept from 1) |
| Findings / to a person / no place / unread pages | 116 / 13 / 3 / 3 | 121 / 0 / 8 / 8 |
| Cases waiting for a person / stuck | 5 / 0 | 0 / **1: drop-16 (long files), PROCESSING** |

- With 0105, every case except the long files is now delivered by the stand-in. All 12 files of the 13: **no leak**.
- **0105's reasons** are shown when the column is there (`findings.person_reason`), with Review's reason as the fallback. This round shows GROUP_INCOMPLETE and TEXT_NOT_READABLE beside LOW_CONFIDENCE.
- **Product fault found (for the design chat, not fixed here: queue A's area):** a case stays **PROCESSING forever** after a person marks checked a page that the *final check* of the finished file held. Seen in both rounds after 0103 (before and after 0105).
  - Sequence: the stand-in confirmed the 6 findings → REDACT done → `DOCUMENT_FINAL_CHECK_HELD pagesToCheck=1` → PENDING_REVIEW → the stand-in marked the new pages to check (long-60 p5, p14, p23, p32) checked → processing resumed (`STAGED_PROCESSING_PLAN … status=PROCESSING`) → **nothing more**.
  - All pipeline jobs DONE, documents REDACTING, no error.
  - Nothing is delivered, so it fails closed, but the owner will meet it in the UI with the long files. So the long-60 DOB leak can't be scored on this head until it is fixed.
  - Run folders kept: `%TEMP%\lexavault-0106\20261127-195424` and `20261127-203343`.
- For 0103's before/after: the handwritten DOB pages (long-60 p23, p32) are now **pages to check**, not delivered unseen.
- The tool now reports a case with no change for 15 minutes as **stuck** instead of waiting (90 minutes before).
- A round stopped half-way cleans up by itself (Ctrl+C, window closed). `--stop` ends one cut off harder: tried by killing the process after start, then `--stop` removed the container.

Found while building, fixed before the proof:
- drops are named in the database only by a hash (`WatchedFolderDropReference`), so they are linked by that hash;
- the watermark makes OCR run words together;
- the vision model answers "There is no text on this page." on a blank page;
- "automatic" is now taken from scorecard 1 in later scorecards.

## Not done / left
- Not run: the full proof set (milestone only).
- The long files can't be scored as delivered on the newest head until the stuck-case fault is fixed.

## For the design chat
- The stuck-PROCESSING fault above: suggest a small task in queue A (the final-check and page-check path, 0099/0100) with a test that marks checked a page the final check held and expects export.

## Questions
1. **The stand-in reviewer (scorecard 2)** is my addition. Without it a round without a person scores leaks for only 2 of 6 cases, and the long-60 leak can't show (the task's own proof). Recommended: keep it as the number to compare rounds by, with scorecard 1 kept as the before-anyone-touches-it record.
2. **Extra redaction shows counts and pages, not the words.** Words that were covered are redacted content, so I don't print them, not even test words. Recommended: keep it so. The run folder in `%TEMP%` holds the delivered files to look at.
3. **Leaks count only kinds that are on.** The demo entity has SSN, DOB, credit score and the locked ones on. Names, accounts and routing numbers are off, so they don't count. Recommended: keep it so. Switching a kind on in a later task (0103) can add leaks earlier rounds couldn't show; the scorecard lists the kinds it counted.
4. **Owner's containers after a Docker restart:** Docker Desktop was not running when I started. After I started it, `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak` and `lexa-keycloak-db` showed as exited (255). I did not start them. `Update-And-Start.ps1` starts `securedoc-postgres` again if needed; the others are the owner's to start.

No other product's name or behaviour in code.
