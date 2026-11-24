# Task 0067: design (staged-pipeline-design.md, section 6, copied)

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
