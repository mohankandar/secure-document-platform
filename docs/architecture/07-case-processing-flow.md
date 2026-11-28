# LexaVault case processing flow

Drawn from the code on `feature/lexavault-r1b-tenant-identity` (head `ab07b75e`, 2026-11-27). It shows what the code
does today. Changes the owner has decided but that are not built yet are listed at the end.

Main classes: `IntakeGate`, `WatchedFolderIntakeService`, `StagedProcessingService` (TEXT / FIND / VISION / REDACT jobs
and the case gate), `DocumentRedactionService` (draw, verify, final check, second check), `ExportJobService`,
`DerivedRoutingService` (splits), `ExportRetentionService` / `EmptyCaseExpiryService`.

## The flow at a glance

![LexaVault case processing flow: three lanes](07-case-processing-flow.svg)

Middle lane: what runs by itself. Right: where a case waits for a person or a destination. Left: where it stops and goes to IT.
The Mermaid diagrams below show the same flow with the job and class names.

## 1. The whole case (Mermaid)

```mermaid
flowchart TD
    subgraph IN["1 · Intake"]
        A1[Manual upload<br/>New case screen]
        A2[Watched folder<br/>waits for the trigger file]
        A3[Push API]
        A4[Polling a source system]
        A5[Scanner]
        GATE{{"Intake gate<br/>room on this server?"}}
        A1 & A2 & A3 & A4 & A5 --> GATE
        GATE -- "no: file waits at its source,<br/>nothing lost" --> GATE
    end

    GATE -- yes --> CASE[Case created + submitted<br/>case fields, priority, sensitivity level]

    subgraph PROC["2 · Processing (jobs, picked up by workers)"]
        T["TEXT job per page batch<br/>typed pages read directly,<br/>scanned pages by OCR"]
        R{"Page routing<br/>handwriting, ID photo,<br/>low OCR confidence?"}
        F["FIND jobs<br/>pattern rules + AI text model"]
        V["VISION job per routed page<br/>vision model"]
        G{{"Case gate<br/>all documents read and searched?"}}
        T --> R
        R -- every page --> F
        R -- routed pages only --> V
        F --> G
        V --> G
    end

    CASE --> T

    G -- "a finding below the<br/>confidence threshold" --> REV1
    G -- "all findings sure" --> RD

    subgraph REVIEW["3 · Review (a person)"]
        REV1["Review page<br/>confirm or dismiss findings,<br/>add boxes"]
    end

    REV1 -- decisions done --> RD

    subgraph RED["4 · Redaction (REDACT job per document)"]
        RD["Draw the boxes<br/>remove comments, metadata, hidden content"]
        VER{"Redaction verified?<br/>• page count same<br/>• every box dark (pages with boxes)<br/>• no hidden value in any page's text<br/>• whole-file check"}
        FC{"Final check<br/>re-read the finished file (text + OCR),<br/>pattern rules again,<br/>vision on picture pages"}
        SC{"Second check<br/>(only if the entity turns it on)<br/>independent AI reading"}
        RD --> VER
        VER -- yes --> FC
        FC -- clean --> SC
    end

    VER -- "no: our defect" --> STOP
    FC -- "a page still shows something<br/>or can't be read" --> REV1
    SC -- "something found" --> REV1
    SC -- "clean, or switched off" --> DONE[Redaction completed]

    subgraph OUT["5 · Export"]
        EX["EXPORT job: whole case<br/>to the master destination"]
        SPL["SPLIT_EXPORT jobs<br/>split documents to their destinations"]
        EX -- delivered --> SPL
    end

    DONE --> EX

    EX -- "destination down<br/>(can't connect, 500/502/503)" --> PAUSE["Destination PAUSED<br/>jobs wait, no attempts used,<br/>health check until it answers"]
    PAUSE --> EX
    EX -- "duplicate answer" --> NODUP[Recorded, never retried]
    EX -- "other failure: spaced retries,<br/>then IT error queue + alert" --> STOP

    SPL --> RET

    subgraph AFTER["6 · After delivery"]
        RET["Retention scheduled<br/>original + redacted copy deleted after<br/>the admin's period (24 h default, max 7 days)"]
        REC[Case record stays: audit trail + Reports<br/>no document content]
        RET --> REC
    end

    STOP["Stopped: worked by IT in Support<br/>counted 'With IT' in Reports"]
```

## 2. How the workers share the work

```mermaid
flowchart LR
    Q[(pipeline_jobs<br/>queue)] --> O1{1. Priority<br/>Urgent → Normal → Low}
    O1 --> O2{2. Entity<br/>round-robin between entities}
    O2 --> O3{3. Queue order<br/>first in, first out}
    O3 --> W[Next free worker<br/>of that stage]
    W --> AI[AI server<br/>1 request at a time per node]
```

- Workers per stage (TEXT, FIND, VISION, REDACT, EXPORT) are measured when the app starts, or set in `application.yml`.
- A large document is split into page batches, so it becomes many TEXT and FIND jobs.
- BULK cases run only while their source's window is open.
- A job whose server stops goes back to the queue. After the attempt limit the case stops with LV-PRC-008.

## 3. Decided, not built yet

| # | Change | Decided |
|---|---|---|
| 1 | **One review stop per case.** The final check runs before the first review, and a page shown to the reviewer counts as checked by a person and is never shown again. | Owner, 2026-11-27 23:21 |
| 2 | No OCR or vision on pages a person already checked. Keep the whole-file value search. | Design chat, 2026-11-27 |
| 3 | AI outage (0116): wait and resume by itself, one IT alert (15 min / 1 h / 1 day). | 2026-11-27 |
| 4 | Undelivered case: deleted after 7 days, IT alert at 1 day plus a final warning (0115). | 2026-11-27 |
| 5 | Round-robin between cases inside one entity as well (proposal, waiting for the owner). | Open |
