# Report U008: Admin › Integrations and the connection editor (findings 16, 24, 25)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE, except item 9 (test **before** saving). That needs a new backend endpoint; it is proposed below and waits for the owner's yes. A saved connection can be tested from step 3 now.

## What was done

### List (`src/studio/admin/IntegrationsArea.tsx`, words in `integrationRules.ts`)

1. **View names:** Receiving, Sending to SDV, Sending by barcode, All. Card titles and one-line descriptions are from the design ("Receiving documents: Systems, folders and scanners that bring documents into LexaVault."). The button is "Add a connection". The tab hint reads "Where documents come from and where they are sent: SDV, and pages by barcode."
2. **Each connection:** its name, one tag and one status line.
   - Tags: "Sends to LexaVault", "LexaVault collects every 15 min" (or "daily at 01:00"), "Folder", "Scanner", "Secure Document Vault", "File share", "Web address".
   - Status lines: "Working · last collected 14 min ago", "Having problems · last collected 1 h ago · last try failed: couldn't reach it", "Switched off", "Ready to receive", "Not checked yet".
   - Failure codes become plain reasons ("couldn't reach it", "its sign-in settings don't work", "pickup couldn't be confirmed to the source", …). An unknown code is not guessed at: the line says "reason not recognised (see Technical details)".
   - The address is in small mono text.
   - **Technical details** in each row, closed by default: provider, sign-in method and whether a key is stored, the account it signs in as, the limits, the next collection, health and failure codes, configuration version and connection ID.
3. **Buttons:**
   - Collect now (polling), Check now (watched folder), Switch on / Switch off, Edit.
   - "Technical retry" is now **Retry**.
   - The row "Test" button moved into the editor's step 3.
   - The switch confirm reads "Switch off <name>?" with plain consequences.
4. **Watched folder once (finding 16):** the separate "Watched-folder intake operations" card is gone.
   - If there is exactly one watched-folder connection, its row carries the folder's status and Check now.
   - Otherwise the installed folder is its own Receiving row, without Edit (it is set by LexaVault's installation settings).
   - Status line: "Working · checked 1 min ago · 2 accepted, 1 waiting for its READY file".
   - The local app has no watched-folder connection, so it shows the folder's own row.
5. **Sending by barcode (`RoutingRules.tsx`):**
   - Rules read as a sentence: "Title pages: barcode “TITLE-001”, top right of the page → Title team folder · destination switched off".
   - The rule editor uses plain labels: Rule name, Look for, Value to match / Text to find, Where on the page, Send to, Switched on.
   - Files that couldn't be sent: "1 file couldn't be sent", then "LN204812 · pages 3–4 → Title pages · couldn't reach it · tried 5 times", with Retry. The package ID is only under Technical details.

### Connection editor

6. **Three steps,** shown as "Step N of 3":
   1. **What:** Receive documents or Send documents, as two option cards with one-line descriptions.
   2. **Details.**
   3. **Test and save:** a summary of what will be saved, Test connection and Save.
   - Editing opens at step 2.
7. **Plain labels with help text:**
   - "How documents arrive" / "Where documents go";
   - "Address";
   - "How LexaVault proves who it is" (for a system that sends to LexaVault: "How the system proves who it is"), with plain names such as "API key" and "Client certificate (mutual TLS)";
   - "Where the key is stored" ("A reference to your secrets store. The key itself is never shown."). It stays a hidden field, as before;
   - "Collect every", in minutes or hours;
   - "Account it signs in with", which was "Authenticated client principal".
8. **Advanced (usually leave as is),** closed: "Wait for an answer", "Try again up to", "Finish incomplete cases within", the file limits, "Cases per collection" and "Confirmation" (was acknowledgement). Its summary line reads, for example, "wait 30 s · try again up to 5 times · … · confirm pickup to the source".
9. **Test connection:**
   - It tests a **saved** SDV, barcode or collecting connection whose settings are unchanged.
   - If anything changed, the button is off: "Testing uses the saved settings. Save your changes first, then test."
   - A new connection says "A new connection can be tested once it is saved. Testing before saving isn't possible yet."
   - Receiving connections that send to LexaVault have nothing to test, and the editor says so.
   - The frontend gained `testPollingConnection` (the endpoint already existed).

### Backend (no schema change)

10. Failed barcode files name the case by its main ID:
    - **Read-only port** `CasePrimaryIdentifierLookup`, implemented by `JdbcCasePrimaryIdentifierLookup`. It uses the same BusinessRecord join as the Support search.
    - **Query** `DerivedDeliveryAttentionQuery`: one lookup for all items.
    - **Answer:** `GET /derived-deliveries/attention` now adds `primaryIdentifierLabel` and `primaryIdentifierValue`, which are null when the case has none. The per-package list is unchanged.

### Where the screen differs from the design (on purpose)

- **"Slow" is shown as "Having problems".** The backend marks a connection DEGRADED whenever its last try failed, and a time-out is recorded as POLL_SOURCE_UNAVAILABLE, like any other failure to reach it. "Slow" could not be stated truthfully.
- **The rule says "barcode “TITLE-001”", not "starting".** Barcodes and QR codes must match exactly (`PdfRoutingMarkerDetectionAdapter`); text is found anywhere in the area.
- **A system that sends to LexaVault says "last worked" or "Ready to receive",** not "last received". Pushed intake does not record a last-success time on the connection.
- **Technical details sit in each row,** not in one line at the bottom of the card.

## Commands run and results

- **Backend:** `cd backend; mvn clean install` passed with 445 tests:
  - domain 31;
  - application 130 (2 new);
  - scan bridge 47;
  - infrastructure 8;
  - API 229.
- **The new SQL on the dev database:** read-only, against `securedoc-postgres`, with the 5 newest packages. All 5 got their main ID. Only the count was printed, never the values.
- **Frontend:**
  - `npm run build`: passed.
  - `npm run test:standalone`: passed:
    - `test:auth` 8;
    - `test:vocabulary` 47 (7 new);
    - `test:ui-framework` 17;
    - `test:oidc-signin` 49 (4 new).
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b` and the new jar. "Frontend did not become ready" appeared again, as in U007; both apps answered (200).
  - Integrations checked as **IT Administrator** (local test role): all four views, plus editor steps 1 and 2. Light and dark, 1440 and 1024 px, in Edge (Playwright).
  - No page errors and no sideways scroll.
  - The live `GET /derived-deliveries/attention` answered 200 (empty list).
  - Nothing was saved, tested, switched or retried on the dev data.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Rules:**
  - no raw hex outside `tokens.css`;
  - no new breakpoint and no new dependency;
  - `uiFrameworkContract.test.ts`, `src/index.css` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- **Backend `DerivedDeliveryAttentionQueryTest` (2 new):**
  - each item gets its case's main ID; a case without one gets null; one lookup covers all packages;
  - no attention items means no lookup.
- **New `src/studio/admin/integrationRules.test.ts` (node, 7):**
  - view names;
  - times and intervals in words;
  - tags;
  - status lines, with no codes and unknown codes not guessed;
  - the watched-folder line;
  - rule sentences, page ranges, the failed-file line by main ID (never the package ID);
  - editor method and the Advanced summary.
- **New `e2e-oidc/adminIntegrations.spec.ts` (Playwright, mocked API, 4).** It also fails on any page error.
  - **Receiving:**
    - view names and counts;
    - tag, status line and mono address per row;
    - the watched folder once, with Check now;
    - codes only under Technical details, which is closed.
  - **Sending by barcode:**
    - Switch on;
    - the rule sentence;
    - the failed file by loan number, with no package ID;
    - Retry calls the retry endpoint.
  - **Add a connection:**
    - the three steps and their labels;
    - Advanced is closed;
    - none of the old words;
    - Test is off for a new connection;
    - what Save sends (purpose, provider, direction, interval 15 minutes, confirmation).
  - **Edit:**
    - Test connection tests the saved SDV connection and shows "The connection works.";
    - after a change it is off and says why.
- **Failures during the work (all in my own tests; the screen was right):**
  - **Barcode test, page error "dests.filter is not a function":** my mock answered the wrong path for barcode destinations. It now uses `/routing-rules/destinations`.
  - **"Watched folder" exact-text count was 0:** the name sits beside its tag, so an exact text match cannot find it. The check now counts rows instead.
  - **Save sent `pollingIntervalMinutes` 900, not 15:** the field showed "1 hour", so typing 15 meant 15 hours, which the screen handled correctly. The test now picks minutes first.
- Failures: none in the final runs.

## Questions for the owner

1. **Test before saving (item 9).** May I add this backend endpoint? (It needs no schema change.)
   - **Endpoint:** `POST /api/v1/integrations/test-draft`, IT Administrator only (`TENANT_INTEGRATION_MANAGE`).
   - **Request:** the same body as saving a connection, plus an optional `connectionId`. With the ID, an empty key reference reuses the stored key.
   - **What it does:** the same check as the SDV, barcode and collecting tests that already exist.
     - It saves nothing and does not change the connection's health.
     - It records one audit line (`INTEGRATION_CONNECTION_TESTED`, marked as a draft).
     - It never sends a customer document.
   - **Answer:** whether it works, and the reason code if not.
   - **In the UI:** step 3's Test connection then works for new and changed connections, and Save stays allowed either way.
2. **"Slow".** Keep "Having problems" (the truth today), or record time-outs separately in the backend so "Slow" can be shown for them?
3. **More than one watched-folder connection.** It is rare: the installation has one folder. In that case the folder's status goes on its own row, so it can appear beside those connections. Fine as it is?
