# LexaVault staged processing pipeline (design)

**Status:** approved 2026-11-21; updated the same day with the owner's later decisions (evidence-based pausing, retry schedule, retention per destination). Built so far: P1 (tasks 0027, 0028).
**Owner's approval:** approved (owner, 2026-11-21), all six decisions in section 12 as recommended.
**Builds on:** the decisions recorded in `claude/Reports-Redesign.md` (export and retries, staged pipeline, priority and migration, all agreed 2026-11-20) and the error codes in `claude/LV-Error-Codes.md`.

## 1. Why

Today one worker takes a case and runs every step in one go: text reading (OCR) → finding (AI) → redaction → export, with the review pause in the middle. Export runs at the end of the same step. So:

- a slow step (usually the AI model) holds a worker for the whole case, and nothing else can use the free CPU;
- a destination that is down makes every case fail at the very end, with retries that sleep inside the worker;
- there is no queue to count, so Reports can't say how much is waiting at each step, or when it will be done;
- Urgent work can't jump ahead, and a migration competes with live work;
- if a worker loses its claim on a case, it keeps going, and two servers can work on one case (inventory #342).

## 2. What changes, and what doesn't

- **Doesn't change:** what each step does. Text reading, finding, redaction, review and the export adapters keep their logic, their rules and their tests. The never-store-a-redacted-value rule, the safety check on the redacted copy and the audit trail stay as they are.
- **Changes:** how the steps are scheduled. Each step becomes a **job in a queue**; workers pick jobs from the queue of the step they serve.
- **No new server:** the queues are a table in LexaVault's existing database. Nothing extra to install, approve or run.

## 3. The stages

| Stage | Job is per | What it does | Next |
|---|---|---|---|
| **Text** | document | Reads the text (native text or OCR) | Find |
| **Find** | document | Finds sensitive data (rules + AI) | When every document of the case is found: Review if needed, else Redact |
| **Review** | case | *A person, not a queue.* Unchanged. | Redact |
| **Redact** | document | Redacts and runs the safety check | When every document is redacted: the case is **Ready for export** |
| **Export** | case × destination | Sends the full case to one destination | Done when every destination has it |
| **Split export** | split document × destination | Sends one split document | Done |

- Documents of one case run in parallel in Text, Find and Redact. The case moves on when all its documents are through (a "gate").
- **Large documents are split into page batches** for Text and Find, gathered again before Redact, so one 500-page document spreads over the workers and can't hold one worker for hours (owner, 2026-11-21).
- **Batches keep their context, set per redaction policy** (owner, 2026-11-21). An AI model can't read 500 pages in one request anyway; the question is what each batch sees:
  - **Batch size and overlap** are policy settings (for example 20 pages, 2 pages shared with the next batch so nothing across a page break is missed; duplicate findings in the overlap are merged by their place on the page).
  - **Document summary** (policy switch): one first pass writes a short summary of the document (the matter, the parties, the kind of document), and every batch gets it with its pages. **On for legal policies, off for mortgage** (faster).
  - **Case instructions** (optional): a reviewer or the source system can add instructions for the AI, for example the parties and what to protect. Every batch gets them.
  - The summary and instructions can hold names, so they're treated like document text: never in logs, audit or Reports, and deleted with the case.
- A case going to two destinations gets two export jobs. One destination being down doesn't hold up the other.

## 4. How a job runs

- **Picking a job:** a worker takes the next job of its stage, in priority order then oldest first. Taking it uses a database lock that skips jobs another worker holds, so no two workers ever get the same job.
- **Claim with a token:** the worker holds a claim that it renews while it works. Each claim has a number (a "fencing token"). When the worker saves its result, the save only counts if its claim is still current. A worker that lost its claim (paused, cut off) can't overwrite anyone. **This fixes #342.**
- **A server stops mid-job:** the claim runs out, and the job goes back to the queue. That counts as an attempt. After **3** attempts the case stops with `LV-PRC-008`, as task 0023 already does.
- **Every status change is audited** with its code, as today. No content.

## 5. Export as its own job, with retries by code (agreed 2026-11-20)

When every document is redacted, the case is **Ready for export**, and one export job per destination is queued. What happens on a failure depends on the code:

| Answer from the destination | Code | What LexaVault does |
|---|---|---|
| Can't connect, timeout, 429, 500, 502, 503 | LV-DLV-001, LV-DLV-002 | Try again **up to 3 times, 5 minutes apart**. If the destination keeps failing (**3 failures in a row across its cases**), **pause** that destination: its cases wait as Ready for export, consuming no attempts. LexaVault checks it every **5 minutes** and resumes **in order** when it answers. |
| "Already have it" (duplicate, 409 with the destination's duplicate code) | LV-DLV-009 | **Counts as exported.** Never retried. |
| Refused the file or the level (other 4xx) | LV-DLV-004, LV-DLV-003 | No automatic retry. The case goes to **"Waiting for you"**. |
| Refused LexaVault's sign-in (401, 403, missing credential) | LV-DLV-005 | Pause the destination (every case would fail) and put it in the **IT error queue**. |
| Answer not understood, not every document confirmed | LV-DLV-007, LV-DLV-008 | Up to 3 tries, 5 minutes apart, then the IT error queue. |

- **"Waiting for you":** a **Processor or Supervisor** resends, selecting many cases at once. **A resend is one attempt.** If it fails, the case goes to the **IT Administrator's error queue** (`LV-DLV-015`) with a **notification**. Notifications come from the shared notifications module; until it exists, Reports' "Needs attention" shows them.
- **The same request each time:** every export carries a stable request id (case, run, destination), so a retry after a lost answer is recognised by the destination as the same request, not a second copy.
- **Pause and resume** are audited, and Support shows a destination's state (Working / Paused since 10:42 / Needs IT).

## 6. Workers that grow with the queue (agreed 2026-11-20)

- Each stage has a **lowest** and a **highest** number of workers. Every few seconds LexaVault sets the number for each stage from its queue: more waiting, more workers, up to the highest.
- **The highest comes from the machine.** The setup command measures it, and IT can change it in Admin:
  - **Text** (OCR, CPU-heavy): about one worker per CPU core, leaving one core free.
  - **Find** (AI): limited by the AI model's capacity, meaning the number of requests each AI server takes at once, summed over the AI servers. Usually the slowest stage.
  - **Redact** (CPU): about half the cores.
  - **Export** (network): a few per destination (default 4), so LexaVault never floods a destination.
- **Measured at every start, not only at setup** (owner's question, 2026-11-21): when LexaVault starts it reads the machine's CPU cores and memory and sets each stage's highest from them, so a bigger machine is used fully after a restart with no change by IT. Memory is a limit too: Text and Redact take no more workers than free memory allows (about 0.5 GB per OCR or redaction worker, measured). IT's own setting, if any, always wins.
- **The AI limit is learned, not just configured** (owner, 2026-11-21). Customers bring their own AI servers, and LexaVault can't know their capacity in advance. So for each AI server LexaVault finds the number of requests at once that it handles well, and adjusts it:
  - **Going up:** while AI work is waiting and answers stay healthy, try one more request at once (for example every few minutes).
  - **Coming down:** if the AI server refuses (429 or 503, honouring Retry-After) or answers get much slower than at the lower number (it's only queueing them), go back down one and remember that as its current ceiling.
  - **Remembered, then re-checked:** the learned ceiling is kept per AI server and tried again after a while (for example an hour, or after a restart), because the customer may upgrade their AI server.
  - **Bounds:** never below 1 and never above IT's own maximum, if IT set one. Shown in Admin, for example "AI server 1: 5 at once (learned)".
- **Inside one server these are threads.** More threads than cores doesn't make CPU work faster, it only makes the machine thrash, so CPU stages stop at about the core count. AI and Export threads mostly wait (on the AI server, on the network), so their limit is the AI capacity and the destination's limit, not the CPU.
- **More servers, more capacity:** every LexaVault server runs its own workers on the same queues, so adding a server adds workers.
- **Nothing is lost** (owner, 2026-11-21):
  - **API: always accepts.** A flood only makes processing take longer. The one exception is a **safety stop**: when free disk or database space drops **below 10%**, the API answers "busy, try again" (`LV-INT-027`, with Retry-After) and takes in again on its own once free space is back **above 15%** (the gap stops it flipping on and off). The thresholds are settings, and the stop is in the API contract so integrators expect it.
  - **Watched folder and polling slow themselves down** when the queue is long: they check less often and take fewer files per check. The files simply wait at the source.
- **Defaults to start with:** lowest 1 per stage; highest as measured. Example (8 cores, one AI server taking 4 at once): Text up to 7, Find up to 4, Redact up to 4, Export up to 4 per destination.

## 7. Priority (agreed 2026-11-20)

- **Urgent / Normal / Low**, Normal by default.
- **Where it's set:**
  - per case at intake (API field, trigger file, New case choice);
  - each source system can have a default;
  - **any reviewer can change it**, and the change is audited.
- **Order** in every stage queue and in the Review list: Urgent → Normal → Low → Bulk, oldest first within each.
- **No starvation:** while Urgent and Normal work are both waiting, **one job in every five** taken from a stage goes to the oldest Normal job. Low gets the same guarantee against Normal.
- Changing a case's priority moves its waiting jobs at once. A job already running finishes.
- **Fairness between entities** (owner, 2026-11-21): within the same priority, workers take turns between entities (and between customers on hosted), so one customer's flood never pushes another customer's daily work back.

## 8. Migration: Bulk (agreed 2026-11-20)

- IT marks a source system as **Bulk**. Its cases are a class below Low.
- **Time window per Bulk source. Default: weeknights 7 PM to 6 AM, plus weekends.** IT can change it per source.
- **Outside the window:** no new Bulk jobs start. A running job finishes. The source can keep dropping files; they wait.
- **Live work always first:** Bulk jobs run only when there are no live jobs waiting in that stage. Even then they use **at most half** of each stage's workers, and **never the last AI worker**, so a case that arrives at 2 AM isn't stuck behind 40,000 migration cases.
- A reviewer can still make one migration case Urgent.

## 9. What people see

- **Operations tab** (Reports): per stage, the number waiting, the number of workers busy, the oldest waiting, and an **ETA** ("about 40 min at this pace": the recent average time per job, times the waiting jobs, divided by the workers). Plus migration progress: "Migration 1917–2004: 12,400 of 40,000 done · about 6 more nights at this pace".
- **Daily tab:** "Not exported yet" splits into **Ready for export**, **Waiting to retry**, **Destination paused**, **Waiting for you**.
- **Support:** destination state; the IT error queue.
- **Case panel:** which stage the case is at, and its priority (reviewers can change it).

## 10. Changes to the stored data (needs the owner's approval)

- A new **jobs** table: stage, case, document, destination, priority, status (Ready / Running / Waiting to retry / Paused / Waiting for you / Needs IT / Done / Stopped), attempts, next try at, claim owner, claim until, claim number, last code.
- **Priority** on the case.
- **Bulk flag and window** on the source system.
- **State** on the destination (Working / Paused / Needs IT) and when it was paused.
- **Workers per stage** settings.
- The existing package claim (the "lease") is replaced by the per-job claim. Existing cases are carried over at upgrade.
- **Clean-up** (owner, 2026-11-21): finished jobs are removed automatically after a set time (default 90 days, as long as Reports needs them). The audit trail keeps the permanent record.

## 11. How it's built (one task each, each proven end to end before the next)

| Step | What | Proof |
|---|---|---|
| **P1** | Jobs table; **export as its own job**; retries by code; pausing a destination; duplicate counts as exported; Waiting for you with bulk resend; IT error queue. Text/Find/Redact still run as today. | A destination switched off mid-run: cases wait, nothing fails, all go out in order when it's back. |
| **P1b** | Job clean-up after the set time. | Old finished jobs are removed; Reports' period still works. |
| **P2** | Text, Find, Redact as per-document jobs with gates; page batches with size and overlap per policy; fairness between entities; the claim number (#342 fixed). | Two LexaVault servers on one database: no case processed twice; one server killed mid-case, the other finishes it. |
| **P2b** | Document summary and case instructions, switched on per policy (legal on, mortgage off). Tested on public court documents. | A 300-page court filing: findings that need the whole matter (for example a party named only on page 1) are caught on later pages with the summary, and missed without it. |
| **P3** | Workers grow with the queue, measured at every start; the AI limit learned per AI server; intake pacing and the safety stop; Admin settings. | 400, 4,000 and 40,000 documents: workers rise to the highest and drop back; the machine is never overloaded. |
| **P4** | Priority: intake field, source default, reviewer change, queue and Review list order, the one-in-five rule. | An Urgent case sent behind 1,000 Normal ones finishes first; Normal still moves. |
| **P5** | Bulk sources and the window; migration progress. | A migration running at night; a live case at 2 AM finishes in normal time; Bulk stops at 6 AM. |
| **P6** | Operations tab stage view and ETA, with the Reports build. | The ETA is within 20% on a test run. |

**Rollout:** a setting chooses the old flow or the staged one. The old flow stays the default until P2 is proven; then the staged flow becomes the default, and the old flow is removed in a later release.

## 12. Decisions for the owner

1. **Go-ahead to change the frozen V1 processing flow.** The steps are unchanged; only how they're scheduled changes.
2. **Approve the stored-data changes** in section 10.
3. **Retries:** 3 tries, 5 minutes apart. Pause a destination after 3 failures in a row; check it every 5 minutes.
4. **Fair share:** one job in five goes to Normal while Urgent is waiting. Bulk uses at most half the workers and never the last AI worker.
5. **Rollout** behind a setting, with the old flow as the default until P2 is proven.
6. **Order:** start with P1 (export), as agreed.
