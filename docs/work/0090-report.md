# Report 0090: sizing guide, first draft

Date: 2026-11-25
Branch: `feature/0090-sizing-guide` (from fd4f6811, PR #33)
Outcome: **DONE as a draft.** Documents only. **Pages per day is laptop-measured, and worked out from single runs.**

## What was done
- `docs/install/sizing-guide.md`: what drives the time, the measured numbers with their hardware, pages per day, adding servers, memory and disk, what is still to be measured. No prices.
- Besides the reports the task names, it uses 0033, 0040, 0044, 0047, 0049, 0060, 0065, 0078 and 0079.
- **The laptop's processor and memory** were in no report, so I read them from the machine (Windows, 2026-11-25): AMD Ryzen 9 9955HX, 16 cores, 32 threads, 62 GB. `nvidia-smi`: RTX 5080 Laptop, 16,303 MiB. The guide cites this report for the processor and memory. I assume it is the machine the earlier runs used: the GPU is the same.
- Setting names, the vision model's single address and the worker rules were checked in `application.yml` and `WorkerLimits.java`.
- **No other product's name or behaviour in code** (no code changed).

## Commands run and results
| What | Result |
|---|---|
| Lines with a number and no citation listed | Left: headings, dates, the list numbers of section 7, and names (GPU, hours, 0086). Every measured number has a citation in its line or table row |
| Every cited report exists | yes: 15, and this one |
| Pages per day worked out again with a small calculation (216,000 / seconds) | matches, rounded down |
| product-name guard | clean |
| Build, backend, frontend | not run locally: no code changed (CI runs) |

## Gaps
- **0086 has no report** (running in queue A). Nothing from it is used; add its numbers when it is done.
- **The times with vision on come from quality runs** ([0064], [0080]) whose reports say they are not performance figures. 0080 gives the range, not each run. The guide says so.
- Rows D and E add two measurements that were never run together.
- Not measured anywhere: several cases at once with real models, a second LexaVault or AI server's gain, disk for each worker, encryption's time, the document summary (0065 is being built).
- No quiet-machine run was made for this task: other sessions use the laptop.

## Questions for the owner
1. The guide cites `docs/work` reports, which customers do not get. Take the citations out of the customer's copy? (recommended: yes, at release; keep them here)
2. May customers see it before the server run? (recommended: no, internal until then)
3. One quiet-machine run on this laptop (package, names, second check together, five cases at once) before the rented run? (recommended: yes, about an hour)
