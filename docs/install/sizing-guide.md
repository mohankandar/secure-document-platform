# Sizing guide for IT (LexaVault)

**DRAFT, laptop-measured, 2026-11-25.** Nothing here was measured on server hardware. The speeds come from one laptop and synthetic documents. Use them to plan a trial, not as a promise, and run your own documents before you decide on hardware.

A number in brackets, such as [0049], is the report it comes from: `docs/work/0049-report.md`. "Worked out" means calculated here from such numbers, not measured.

## 1. The machine it was measured on

| Part | What |
|---|---|
| Graphics card (GPU) | NVIDIA RTX 5080 Laptop, 16,303 MiB of memory [0033] |
| Processor | AMD Ryzen 9 9955HX, 16 cores (32 threads) [0090] |
| Memory | 62 GB usable [0090] |
| AI server | Ollama on the same laptop: 0.40.17 in [0033] and [0040], 0.40.18 in [0080] and [0083] |
| Text model | `qwen3.5:9b`, 6.6 GB, 4-bit [0033] |
| Vision model (reads page images) | `qwen3-vl:8b-instruct`, 6.2 GB, 4-bit [0033] |

LexaVault, the database, text recognition and the AI shared this one machine. Other work ran on it during several runs ([0064], [0067], [0080]), and it slowed down by up to 17% in one session [0040].

## 2. What drives the time

| Driver | Effect measured |
|---|---|
| **Pages** | 0.80 s a page for reading the text and the text model in the 60-page package, one request at a time [0033] |
| **Text or scanned pages** | A scanned page needs text recognition first: 0.85 s a page [0033]. It uses the processor, not the GPU |
| **Pages the vision model reads** | Handwriting, checks, ID copies, faxes, poor scans: 3.14 s a page on average, nine of ten within 4.34 s [0040]. With other work on the AI server, 17 to 21 s on average, waiting included [0064]. Some plain scans go there too: 3 of 16 [0064] |
| **Names on** | One more AI request for each group of pages. 60 pages: 50 and 55 s without, 74 s with, 95 s in a run where one request timed out [0072] |
| **Second check on** | 2.8 to 3.8 s a page. Pages are checked one after the other, so 100 pages add about 5 minutes [0083] |
| **Document summary on** | **Not measured.** Not part of LexaVault yet when this was written [0065] |
| **Searchable redacted pages on** | 0.95 s for each redacted page [0044] |
| **People** | A case that stops for review waits for a person. At the default settings nearly every case with a vision page is reviewed [0064] |

Vision, names, the second check and searchable pages are set for each entity.

## 3. The measured 60-page package

A mostly typed loan package of 60 pages: 56 typed (3 of them signature pages) and one each of a form, an ID copy, a check and a fax [0033]. One case at a time, one LexaVault server, models already loaded, from sending it until it waits for review.

| Run | Seconds |
|---|---|
| In steps, without vision | 34.7 and 36.0 [0049] |
| With vision, newest build tested | 46 to 51 [0080] |
| With vision, an earlier build in the same runs | 55 to 64 [0080] |
| With vision (5 pages), three sessions using the laptop | 101.7 [0064] |
| With vision and names | 74; 95 with one timeout [0072] |

The runs in [0064] and [0080] were made to check quality while other work ran. Their reports say the times are not fair or not performance figures. They are used here because nothing better has been measured with vision on.

Redaction and export come afterwards. For this package: redaction 1.98 s, or 8.59 s with searchable pages; final check 0.41 or 0.43 s; reading the pages with findings again 3.04 s [0047]. Export to your own system was not measured.

The runs in [0049] without vision used the staged pipeline, which is the only one since task 0121 (the earlier `inline` mode took 46.6 s for the same package [0049]; it is removed). Nothing has to be set for it.

## 4. Pages per day (**draft, laptop-measured**)

**One row group only: GPU RTX 5080 Laptop 16 GB, processor Ryzen 9 9955HX** [0033], [0090], one LexaVault server and one AI server on that machine. No other GPU or processor has been measured.

The last three columns are **worked out** from the second, as if such packages followed each other without a gap. They leave out redaction, export and people. Plan with a good margin below the lower figure until a trial on your own hardware is done.

| Work | Measured | Pages per hour | In 8 hours | In 24 hours |
|---|---|---|---|---|
| A. Typed package, no vision | 34.7–36.0 s for 60 pages [0049] | 6,000–6,200 | 48,000–49,700 | 144,000–149,300 |
| B. Typed package, vision on | 46–64 s for 60 pages [0080] | 3,300–4,600 | 27,000–37,500 | 81,000–112,600 |
| C. As B, names on | 74–95 s for 60 pages [0072] | 2,200–2,900 | 18,100–23,300 | 54,500–70,000 |
| D. As B, second check on | B [0080] plus 2.8–3.8 s a page [0083] | 730–1,000 | 5,900–8,000 | 17,700–24,200 |
| E. As C, second check on | C [0072] plus 2.8–3.8 s a page [0083] | 660–890 | 5,300–7,100 | 16,000–21,400 |
| F. Every page read by vision (its requests only) | 14.0–21.4 pages a minute [0040] | 840–1,280 | 6,700–10,200 | 20,100–30,800 |

- Figures are rounded down.
- D and E add measurements that were never run together. The second check was measured on 25 short documents of 48 pages in all [0083]; on a typed package it may differ.
- On a busy machine it is less: 101.7 s for the package [0064] is about 2,100 pages an hour (worked out).
- A to E are one case at a time. Several cases at once with the real models were not measured. They share the GPU: text and vision requests together took 9% less time than one after the other [0040].
- Without a usable GPU it is not practical: about 50 s a page when the AI server fell back to the processor [0040].

## 5. Adding capacity

**First find the slow step.** Admin, Platform operations shows what waits at each step, its workers and the time left [0078]. The time left was within 7% on the longer load, and up to 54% too long on a load of about 2 minutes [0078].

**Workers grow by themselves** in mode `staged`. Every 5 s each step gets workers for its queue, from 1 up to its highest, which is worked out when LexaVault starts [0067]:

| Step | Highest |
|---|---|
| Reading text | Processor cores less 1 (threads count as cores) [0067] |
| Redacting | Half the cores [0067] |
| Both together | Free memory divided by 512 MB [0067] |
| Finding, and looking at pages | What the AI servers take [0067] |
| Exporting | 4 for each destination [0067] |
| All steps together | 10 below the database connections, which are 40 for each server [0067] |

You can set your own maximum for each step in Admin [0067]. In the 4,000-document run the database connections were the limit: 5 redaction workers where half the cores would be 16 [0067]. On a large machine raise `SECUREDOC_DB_CONNECTIONS` and PostgreSQL's `max_connections` together. Two servers at 40 stay below PostgreSQL's default of 100 [0067].

**Add an AI server (GPU) when Find or Look at pages is the slow step.** LexaVault learns how many requests each AI server takes at once: it starts at 1 (or where it was), adds one while requests wait, takes one away when the server refuses or only queues, and tries again after an hour or a restart [0067]. `SECUREDOC_AI_REQUESTS_AT_ONCE` is your own maximum [0067].

- On the laptop 4 vision requests at once gained nothing over 2. With `OLLAMA_NUM_PARALLEL=2`, 2 at once gave 21.4 pages a minute against 16.4 with the default, for about 1.1 GB more GPU memory for each model [0040].
- Several AI servers for the text model are listed in `SECUREDOC_AI_POOL_NODE_URLS`, with `SECUREDOC_AI_POOL_ENABLED=true`. The vision model has one address, `SECUREDOC_AI_VISION_BASE_URL`, so a first step is to give text and vision a GPU each. **The gain of either was not measured.**
- The learning was proven against stand-in AI servers, not real ones. In the load runs it ended at 5 and at 6 with a stand-in that takes 5 [0067].

**Add a LexaVault server when Reading text or Redacting is the slow step.** Servers share the queue, and one takes over from another that stops: 20 cases were finished 246 s after one of two servers was killed [0049].

- A second LexaVault server does not make the AI server faster. Each server limits only its own requests, so together they can send one AI server more than one would [0049].
- The gain in pages per day from a second server was not measured.

**Use the night.** A migration can run as Bulk, in a time window, behind live work [0079].

## 6. Memory and disk

**GPU memory.** With both models loaded: 14,385 MiB, or 15,547 MiB with two requests at once, of 16,303 MiB [0040]. A 16 GB card is then nearly full, so plan at least 16 GB for one GPU that holds both models.

**Server memory**

| What | Number |
|---|---|
| Counted for each text or redaction worker | 512 MB; one text recognition process used up to 112 MB [0067] |
| Most used by the app and text recognition, 4,000 documents, at most 11 text and 5 redaction workers, app heap limit 4 GB | 2,799 MB [0067] |
| Most used, 1,031 cases | 3,208 MB [0073] |
| Memory limit of those runs | 8,192 MB [0067], [0073] |

They used a stand-in AI, and vision was not under load [0067]. Memory for the AI server, PostgreSQL and the key service comes on top and was not measured.

**Disk.** Disk for each worker was **not measured**. What is known:

- The redacted 60-page package is 13,070,987 bytes (about 13 MB), 0.1% more with searchable pages [0044].
- Originals, redacted copies, extracted text and findings are stored encrypted until your retention ends [0060]. Plan from your own documents: the size of a day's originals and redacted copies, times the days you keep them, plus your backups.
- Work files and page images are in the server's temporary folder for a short time. Use an encrypted disk for it (`KEY_SERVICE.md`).
- Below 10% free LexaVault takes in no new cases until it is above 15% again [0067]. Keep well above 15%.
- Database space is checked only if you set `SECUREDOC_INTAKE_SPACE_DATABASE_SIZE_GB` [0067].

## 7. Still to be measured (the rented-cloud run on server hardware)

1. The table in section 4 for server GPUs and processors, a row group for each, on a quiet machine.
2. Several cases at once with the real models, and the learned limit against real AI servers.
3. What a second LexaVault server and a second AI server bring.
4. Text and vision on a GPU each.
5. The document summary; names and the second check together on one package.
6. Disk for each worker, database growth, and the time encryption takes.
7. Memory with large documents, and with vision under load.
8. A large queue: the load runs had at most 4,000 documents, with a stand-in AI; 40,000 were not run [0067].
9. The results of task 0086, which had no report when this was written.
10. Real customer documents: all samples so far are synthetic [0033].
