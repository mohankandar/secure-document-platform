# Test every way a document can come in

One command tries all the ways LexaVault takes documents in, and prints what happened to each.

## Run it

1. Docker Desktop is running.
2. Double-click `Run-IntakePaths.cmd`, or from the repository root:

```
node test-tools\intake-paths\intake-paths.mjs
```

It takes about 5 minutes. The last line before "Details" is the answer: **ALL PASS**, or how many failed.

It does not touch your local LexaVault, its database or its folders. It starts its own database, app and Scan Bridge, uses made-up documents, and stops them all at the end.

### Before the first run

Build once, from the repository root: `.\Build-LexaVault-All.ps1` (again after each code change). If you forget, the run says "not built yet" and stops. Node 22, Java 21 and Docker are needed; they are already on this machine.

## What it tries

| Way in | How |
|---|---|
| Manual upload | as the New case screen does |
| Watched folder, with trigger | a folder with a PDF and its `manifest.json` |
| Watched folder, no trigger | the same without `manifest.json`: LexaVault must wait; the file comes later |
| File polling | LexaVault fetches from a small test source system |
| Scanner bridge (simulated) | the Scan Bridge with its simulator, no scanner needed |
| API | another system sends the document, with and without a password |

## What it checks for each

| Check | A PASS means |
|---|---|
| Accepted | a good PDF is taken in |
| Processed | it is redacted and exported (COMPLETED) |
| Names the entity | the case is stored under the demo entity, and another entity can't open it |
| Wrong type | a text file, and a text file called `.pdf`, are refused |
| Too big | a file over the limit, or with too many pages, is refused |
| Password-protected | refused everywhere except the API with the right password |
| Unknown entity | refused, or left where it is; no case is made |
| Server full | the safety stop: "busy, try again later"; folders wait and are taken later |

Each refusal shows its `LV-` code. The meaning of a code is in `docs\api\error-codes.md`.

At the end it also checks that no redacted value and no password is in the database, the logs, the answers, the audit export or the printed result.

## If something fails

Send the printed result and the folder named after "Details" (it holds made-up test values only). If it says the container `lexavault-0077-pg` exists already, another run is going, or the last one was closed half-way: wait, or run `docker rm -f lexavault-0077-pg`.
