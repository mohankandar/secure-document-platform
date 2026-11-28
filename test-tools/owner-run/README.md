# Owner test round, with a scorecard (task 0106)

One command runs the whole test set through LexaVault from a clean start and writes a scorecard. Every round starts from zero and is scored the same way, so rounds can be compared.

## Run a round

1. Docker Desktop and Ollama are running. The backend is built (`.\Build-LexaVault-All.ps1`, or `C:\dev\lexavault\Update-And-Start.ps1`).
2. From the repository root:

```
node test-tools\owner-run\owner-run.mjs
```

or double-click `Run-OwnerRound.cmd`.

It takes about **15 minutes** on this laptop (both scorecards; the three long files and the vision readings take most of it). Your own LexaVault can keep running: the round uses its own ports.

What it does:

- starts a **throwaway** database (container `lexavault-0106-pg`, port 55406) and LexaVault LOCAL against it, with the real models (text `qwen3.5:9b`, vision `qwen3-vl:8b-instruct`);
- drops the test set from `C:\dev\lexavault\test-pdfs` into the watched folder: the 8 folders of `watched-drops-run2` (the 13 files and `long\*.pdf`), each with **new contract numbers**;
- waits until every case is finished or waits for a person, then **scores before anyone touches it** (scorecard 1, `before-review`);
- then, with no person, **accepts every proposal as it stands**: each value finding with a place is redacted as proposed, each page to check is marked checked with nothing added, and the case is approved. A finding without a place can't be redacted without a mark (task 0100), so its case stays held. Then it scores the delivered files again (scorecard 2, `as-proposed`). This shows what LexaVault's own proposals would deliver, so rounds can be compared without a person. `--no-accept` skips it;
- stops the app and removes the throwaway database.

It never touches your containers (`securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak`, `lexa-keycloak-db`), your normal database or your folders.

## Score again after your review

```
.\Stop-LexaVault-Local.ps1
node test-tools\owner-run\owner-run.mjs --review
```

The same round, but the app runs on port 8080 and **stays up** after the first scorecard. Start the screen if it isn't running (`.\restart-ui.cmd`), open http://localhost:5173 and review the waiting cases as usual. Then:

```
node test-tools\owner-run\owner-run.mjs --after
```

It waits for the exports your review released, scores again, shows what the review changed, and ends the round (app stopped, throwaway database removed). `--stop` ends a kept round without scoring.

## The scorecard

Markdown files per round in `test-tools\owner-run\results\`: `<round>-before-review.md`, then `<round>-as-proposed.md` (unattended round) or `<round>-after-review.md` (after `--after`), plus a short summary in the window. The second one also lists what changed since the first: per case, the status and each finding whose decision changed. It shows kinds, pages, counts and codes only, never a value from a document.

| Line | What it means | Good |
|---|---|---|
| **Leaks** | a value of a kind that is on for the entity (or locked: card, CVV, PIN) is still readable in a **delivered** file. Read three ways: the text layer (all text, also hidden or tiny), OCR, and the vision model for pages that were images in the original (scans, photos, handwriting, which OCR can't read). "read by" says which way found it. | 0 |
| **Extra redaction** | visible words of the original page that the delivered page lost, apart from where the sensitive values stand. Both pages are read the same way from what is visible, so hidden text that was removed doesn't count: by OCR, or by the vision model where the original page is an image (scans, handwriting). A covered label ("SSN", "DOB") or a name whose kind is off counts too. A few words can be reading noise. Pages LexaVault holds as unreadable are left out. | low |
| **Automatic** | cases finished with no person: no finding waiting in Review | high |
| **Sent to a person** | findings waiting in Review, with Review's reason (low confidence, place uncertain, policy) | low |
| **Without a place** | findings with no box on the page | 0 |
| **Unread pages** | pages LexaVault could not read and holds for a person | as expected (file 10, page 3) |
| **Intake** | each folder: taken (a case), refused (with its `LV-` code; meaning in `docs\api\error-codes.md`), or waiting (no manifest) | drop-14 refused, drop-15 waiting, the rest taken |
| **Time** | from the case being made until it was finished or waited for a person | |

Expected answers: `expected.json` (files 01 to 13, from `test-pdfs\gen.py` and `test-pdfs\README.md`) and `test-pdfs\long\*-expected.txt`. A leak is counted only for a value whose kind is on, so switching a kind on in a later version can add leaks the earlier rounds could not show.

A file is scored only once it is delivered: LexaVault makes the redacted copy only after the review, and no one can download it before. So scorecard 1 scores only the cases that needed no person; scorecard 2 (or `--after`) scores the rest.

## If something fails

- "the container lexavault-0106-pg exists already": another round is running, one was kept with `--review`, or one was cut off (the laptop slept, the process was killed). End it with `--after` or `--stop`. Ctrl+C or closing the window ends a round cleanly by itself.
- "port 8080 is in use": stop LexaVault first (`.\Stop-LexaVault-Local.ps1`); only `--review` needs 8080.
- The run folder (printed at the start, under `%TEMP%\lexavault-0106\`) holds the logs, the delivered files and what each reading found. It holds made-up test values only.

## Update-And-Start and VS Code (task 0120)

`C:\dev\lexavault\Update-And-Start.ps1` refuses to start while VS Code's Java extension (redhat.java, its Java language server) runs: it holds files under `target\` and the build would fail halfway ("Failed to delete ...\target\classes"). It stops before changing anything, with:

> VS Code's Java extension is running and locks build files. Close VS Code (or run 'Java: Clean Java Language Server Workspace' and close it), then run this again.

It never stops that process itself. The check (`scripts\lib\LexaVault-JavaLanguageServer.ps1`, tested by `scripts\testing\Test-LexaVault-JavaLanguageServer.ps1`) runs before the pull and again just before the build. `Run-OwnerRound.cmd` builds nothing (it starts the jar already built), so it has no such check.
