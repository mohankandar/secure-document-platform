import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DEFAULT_MAX_PDF_SIZE_BYTES, SOURCE_FILES_HINT, SOURCE_MAX_FILES, SOURCE_MAX_PAGES, SOURCE_PAGES_HINT, VIEWS, advancedSummary, capToCaseLimits, ago, folderCheckResult, attentionLine, attentionTitle, connectionStatus, connectionTag, draftTestResult, everyText, failureReason,
  methodOf, pagesText, ruleSentence, slashParts, statusText, watchedFolderStatus,
} from './integrationRules.ts';

const NOW = Date.parse('2026-11-18T12:00:00Z');
const minsAgo = (m: number) => new Date(NOW - m * 60000).toISOString();

test('view names are the vocabulary words', () => {
  assert.deepEqual(VIEWS.map((v) => v.label), ['Receiving', 'Sending to the document store', 'Sending by barcode', 'All']);
  for (const v of VIEWS) assert.doesNotMatch(`${v.label} ${v.title} ${v.note}`, /intake|master|derived/i);
});

test('times and intervals in words', () => {
  assert.equal(ago(minsAgo(0), NOW), 'just now');
  assert.equal(ago(minsAgo(14), NOW), '14 min ago');
  assert.equal(ago(minsAgo(60), NOW), '1 h ago');
  assert.equal(ago(minsAgo(3 * 1440), NOW), '3 days ago');
  assert.equal(everyText(15), '15 min');
  assert.equal(everyText(60), 'hour');
  assert.equal(everyText(120), '2 h');
  assert.equal(everyText(1440), 'day');
});

test('one tag says what each connection does', () => {
  const base = { pollingScheduleType: null, pollingIntervalMinutes: null, pollingLocalTime: null };
  assert.equal(connectionTag({ ...base, purpose: 'INGESTION_SOURCE', providerType: 'PUSH_API' }), 'Sends to LexaVault');
  assert.equal(connectionTag({ ...base, purpose: 'INGESTION_SOURCE', providerType: 'HTTP_API', pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 15 }), 'LexaVault collects every 15 min');
  assert.equal(connectionTag({ ...base, purpose: 'INGESTION_SOURCE', providerType: 'HTTP_API', pollingScheduleType: 'DAILY', pollingLocalTime: '01:00' }), 'LexaVault collects daily at 01:00');
  assert.equal(connectionTag({ ...base, purpose: 'INGESTION_SOURCE', providerType: 'WATCHED_FOLDER' }), 'Folder');
  assert.equal(connectionTag({ ...base, purpose: 'INGESTION_SOURCE', providerType: 'SCANNER' }), 'Scanner');
  assert.equal(connectionTag({ ...base, purpose: 'DOCUMENT_STORE_DELIVERY', providerType: 'HTTP_API' }), 'Document store');
  assert.equal(connectionTag({ ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'FILE_SHARE' }), 'File share');
  assert.equal(connectionTag({ ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'HTTP_API' }), 'Web address');
});

test('one plain status line per connection; a failure reads as its LV title and code, never an internal code', () => {
  const c = { enabled: true, healthStatus: 'HEALTHY', lastSuccessAt: minsAgo(14), lastFailureAt: null, lastFailureCategory: null, purpose: 'INGESTION_SOURCE' as const, providerType: 'HTTP_API' as const };
  assert.equal(statusText(connectionStatus(c, NOW)), 'Working · last collected 14 min ago');
  const failing = { ...c, healthStatus: 'DEGRADED', lastSuccessAt: minsAgo(60), lastFailureAt: minsAgo(5), lastFailureCategory: 'POLL_SOURCE_UNAVAILABLE' };
  // Task 0025: the words come from the LV code registry (was "couldn't reach it").
  assert.equal(statusText(connectionStatus(failing, NOW)), "Having problems · last collected 1 h ago · last try failed: Source system can't be reached · LV-INT-040");
  assert.equal(connectionStatus(failing, NOW).tone, 'warn');
  // A failure older than the last success is not shown.
  assert.equal(statusText(connectionStatus({ ...failing, healthStatus: 'HEALTHY', lastFailureAt: minsAgo(120) }, NOW)), 'Working · last collected 1 h ago');
  assert.equal(statusText(connectionStatus({ ...c, enabled: false }, NOW)), 'Switched off');
  assert.equal(statusText(connectionStatus({ ...c, purpose: 'DOCUMENT_STORE_DELIVERY', providerType: 'HTTP_API' }, NOW)), 'Working · last sent 14 min ago');
  assert.equal(connectionStatus({ ...c, providerType: 'PUSH_API', healthStatus: 'UNKNOWN', lastSuccessAt: null }, NOW).word, 'Ready to receive');
  assert.equal(connectionStatus({ ...c, purpose: 'DOCUMENT_STORE_DELIVERY', healthStatus: 'UNKNOWN', lastSuccessAt: null }, NOW).word, 'Not checked yet');
  assert.equal(connectionStatus({ ...c, healthStatus: 'SOMETHING_NEW' }, NOW).word, 'Status not recognised');
  // An unknown failure code is not shown or guessed at: it reads as LV-SYS-001 (was "reason not recognised").
  const unknown = statusText(connectionStatus({ ...failing, lastFailureCategory: 'NEW_CODE_X' }, NOW));
  assert.doesNotMatch(unknown, /NEW_CODE_X/);
  assert.match(unknown, /Something went wrong · LV-SYS-001$/);
  // The server's own lvCode wins.
  assert.equal(failureReason('POLL_SOURCE_UNAVAILABLE', 'LV-INT-041'), 'Source system answered with an error · LV-INT-041');
  for (const code of ['POLL_AUTH_CONFIGURATION_ERROR', 'RETRIEVAL_FAILED', 'DELIVERY_IO', 'SOURCE_ACKNOWLEDGEMENT_FAILED', 'ROUTING_MARKER_AMBIGUOUS']) {
    assert.doesNotMatch(failureReason(code), /[A-Z]{3,}_/);
  }
});

test('the watched folder line: checked, accepted, waiting for its ready file', () => {
  const w = { enabled: true, lastScanAt: minsAgo(1), lastScanOutcome: 'SUCCESS', triggerFileName: 'READY', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 } };
  assert.equal(statusText(watchedFolderStatus(w as never, NOW)), 'Working · checked 1 min ago · 2 accepted, 1 waiting for its READY file');
  assert.equal(statusText(watchedFolderStatus({ ...w, enabled: false } as never, NOW)), 'Switched off');
  assert.equal(watchedFolderStatus({ ...w, lastScanOutcome: 'FAILED' } as never, NOW).word, 'Having problems');
});

test('barcode rules read as a sentence, and failed files name the case by its main ID', () => {
  assert.equal(ruleSentence({ destinationLabel: 'Title pages', markerType: 'BARCODE', markerValue: 'TITLE-001', scanRegion: 'TOP_RIGHT' }, 'Title team folder'),
    'Title pages: barcode “TITLE-001”, top right of the page → Title team folder');
  assert.equal(ruleSentence({ destinationLabel: 'SC', markerType: 'TEXT_PATTERN', markerValue: '7777', scanRegion: 'BOTH' }, undefined),
    'SC: text “7777”, top right or bottom left of the page → a place that no longer exists');
  assert.equal(pagesText([4, 3]), 'pages 3–4');
  assert.equal(pagesText([7]), 'page 7');
  assert.equal(pagesText([2, 5, 6, 7]), 'pages 2, 5–7');
  const item = {
    derivedDeliveryId: 'd1', packageId: '6f1c0000-0000-0000-0000-000000000001', sourceDocumentId: 'x', exportCycle: 1, destinationLabel: 'Title team folder',
    markerType: 'BARCODE' as const, pageNumbers: [3, 4], status: 'ATTENTION_REQUIRED', attemptCount: 5, failureCategory: 'DELIVERY_IO', retryable: true,
    primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: 'LN204812',
  };
  assert.equal(attentionLine(item), "LN204812 · pages 3–4 → Title team folder · Destination can't be reached · LV-DLV-001 · tried 5 times");
  assert.equal(attentionLine({ ...item, lvCode: 'LV-DLV-002' }), 'LN204812 · pages 3–4 → Title team folder · Destination busy or down · LV-DLV-002 · tried 5 times');
  assert.doesNotMatch(attentionLine({ ...item, primaryIdentifierValue: null }), /6f1c/);
  assert.equal(attentionTitle(1), "1 file couldn't be sent");
  assert.equal(attentionTitle(2), "2 files couldn't be sent");
});

test('the editor: method from a saved connection, and the advanced settings in words', () => {
  assert.equal(methodOf({ purpose: 'INGESTION_SOURCE', providerType: 'HTTP_API' }), 'POLL');
  assert.equal(methodOf({ purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'FILE_SHARE' }), 'BARCODE_SHARE');
  assert.equal(methodOf({ purpose: 'DOCUMENT_STORE_DELIVERY', providerType: 'HTTP_API' }), 'DOCUMENT_STORE');
  assert.equal(advancedSummary({ timeoutSeconds: 30, retryMaxAttempts: 5, incompleteWindowHours: 24, maxDocumentsPerIntake: 50, maxPdfSizeBytes: 50 * 1048576, maxPdfPages: 500, acknowledgementMode: 'SOURCE_PICKUP_ACK', purpose: 'INGESTION_SOURCE' }),
    'wait 30 s · try again up to 5 times · finish incomplete cases within 24 h · up to 50 files, 50 MB, 500 pages · confirm pickup to the source');
});

test('a test before saving reads as works or the LV title and code, never an internal code', () => {
  assert.deepEqual(draftTestResult({ works: true, reasonCode: null }), { tone: 'ok', text: 'The connection works.' });
  assert.equal(draftTestResult({ works: false, reasonCode: 'DELIVERY_IO' }).text,
    "The connection didn't work: Destination can't be reached · LV-DLV-001. Check the address and sign-in, then test again.");
  assert.equal(draftTestResult({ works: false, reasonCode: 'ADDRESS_NOT_ALLOWED' }).text,
    "The connection didn't work: Connection settings not valid · LV-CFG-003. Cloud metadata addresses are always refused.");
  assert.equal(draftTestResult({ works: false, reasonCode: 'ADDRESS_NOT_RESOLVED' }).text,
    "The connection didn't work: Connection test failed · LV-CFG-004. Check the address and sign-in, then test again.");
  const unknown = draftTestResult({ works: false, reasonCode: 'SOMETHING_NEW' });
  assert.equal(unknown.text, "The connection didn't work: Something went wrong · LV-SYS-001. Check the address and sign-in, then test again.");
  assert.doesNotMatch(unknown.text, /SOMETHING_NEW/);
  assert.equal(failureReason('CONNECTION_TEST_FAILED'), 'Connection test failed · LV-CFG-004');
  assert.equal(failureReason('DOCUMENT_STORE_TEST_UNAVAILABLE'), 'Connection test failed · LV-CFG-004');
});

test('an address wraps only after a slash, never inside "://" (U020)', () => {
  assert.deepEqual(slashParts('https://northwind.okta.com/oauth2/aus9lexa'), ['https://', 'northwind.okta.com/', 'oauth2/', 'aus9lexa']);
  assert.deepEqual(slashParts('\\\\server\\share\\in'), ['\\\\', 'server\\', 'share\\', 'in']);
  assert.deepEqual(slashParts('/data/inbox/'), ['/', 'data/', 'inbox/']);
  assert.deepEqual(slashParts('plain'), ['plain']);
  assert.deepEqual(slashParts(''), []);
});

/* ---------- Task 0023 C4: split-delivery routing codes in plain words ---------- */

test('0023 C4 / 0025: each split routing and delivery code reads as its LV title and code, with no internal code in it', () => {
  // Task 0025: the words come from the LV code registry; codes that share an LV code now read the same.
  const expected: Record<string, string> = {
    ROUTING_MASTER_UNAVAILABLE: 'Nothing ready to export · LV-DLV-011',
    ROUTING_FAILED: "Split document couldn't be made · LV-DLV-013",
    DERIVED_DOCUMENT_BUILD_FAILED: "Split document couldn't be made · LV-DLV-013",
    DERIVED_DESTINATION_NOT_FOUND: 'Destination not set up or switched off · LV-DLV-006',
    DERIVED_DESTINATION_WRONG_PURPOSE: 'Destination not set up or switched off · LV-DLV-006',
    DERIVED_DESTINATION_DISABLED: 'Destination not set up or switched off · LV-DLV-006',
    DERIVED_ARTIFACT_UNAVAILABLE: 'Nothing ready to export · LV-DLV-011',
    DERIVED_SOURCE_NOT_FOUND: 'Nothing ready to export · LV-DLV-011',
    ROUTING_MARKER_AMBIGUOUS: 'Split rule matched more than once · LV-DLV-012',
    DERIVED_ROUTING_POST_EXPORT_FAILED: "Split document couldn't be made · LV-DLV-013",
  };
  for (const [code, words] of Object.entries(expected)) {
    assert.equal(failureReason(code), words, code);
    assert.equal(failureReason(code.toLowerCase()), words, `${code} in lower case`);
    assert.doesNotMatch(failureReason(code), /[A-Z]{3,}_/, code);
  }
  // In the attention list line.
  assert.equal(attentionLine({ primaryIdentifierValue: 'LN204812', pageNumbers: [3, 4], destinationLabel: 'Title pages', markerType: 'BARCODE', failureCategory: 'DERIVED_DOCUMENT_BUILD_FAILED', attemptCount: 1 } as never),
    "LN204812 · pages 3–4 → Title pages · Split document couldn't be made · LV-DLV-013 · tried 1 time");
});

/* ---------- Task 0023 D3: the size limit default matches the backend ---------- */

test('0023 D3: with no size saved, a receiving connection reads 50 MB (the backend default and multipart limit)', () => {
  assert.equal(DEFAULT_MAX_PDF_SIZE_BYTES, 52428800);
  assert.equal(advancedSummary({ acknowledgementMode: 'SOURCE_PICKUP_ACK', purpose: 'INGESTION_SOURCE' }),
    'wait 60 s · try again up to 2 times · finish incomplete cases within 24 h · up to 50 files, 50 MB, 500 pages · confirm pickup to the source');
});

/* ---------- Task 0023: "Check now" on the watched folder says when this server did not check it ---------- */

test('0023: the folder check toast gives the counts when checked, and a plain line for each other outcome', () => {
  const counts = { discovered: 3, waitingForTrigger: 0, accepted: 2, rejected: 1, duplicates: 0 };
  const checked = 'Folder checked: 2 accepted, 1 refused, 0 already received.';
  assert.deepEqual(folderCheckResult({ ...counts, outcome: 'SCANNED' }), { text: checked, tone: 'neutral' });
  // An older server sends no outcome: it did check.
  assert.deepEqual(folderCheckResult(counts), { text: checked, tone: 'neutral' });
  assert.deepEqual(folderCheckResult({ ...counts, outcome: 'STANDBY' }),
    { text: 'Not checked here: this LexaVault server is on standby, and another one checks the folder.', tone: 'neutral' });
  assert.deepEqual(folderCheckResult({ ...counts, outcome: 'NOT_CONFIGURED' }),
    { text: 'Not checked: the watched folder isn’t set up in this installation.', tone: 'danger' });
  assert.deepEqual(folderCheckResult({ ...counts, outcome: 'COORDINATION_DEGRADED' }),
    { text: 'Not checked: the LexaVault servers couldn’t agree which one checks the folder. Try again in a minute.', tone: 'danger' });
  // An outcome this screen does not know is not read as "checked", and the code is not shown.
  const unknown = folderCheckResult({ ...counts, outcome: 'SOMETHING_NEW' });
  assert.deepEqual(unknown, { text: 'The folder wasn’t checked.', tone: 'danger' });
});

/* ---------- Task 0039: receiving connections stop at the case limits ---------- */

test('0039: a receiving connection can promise at most 50 files per case and 500 pages per file, and says why', () => {
  assert.equal(SOURCE_MAX_FILES, 50);
  assert.equal(SOURCE_MAX_PAGES, 500);
  assert.equal(SOURCE_FILES_HINT, 'A case holds up to 50 files.');
  assert.equal(SOURCE_PAGES_HINT, 'A case takes PDFs of up to 500 pages.');
});

test('0039: a source saved before the cap reads as the cap; lower and missing values are kept', () => {
  assert.deepEqual(capToCaseLimits({ maxDocumentsPerIntake: 1000, maxPdfPages: 2000 }), { maxDocumentsPerIntake: 50, maxPdfPages: 500 });
  assert.deepEqual(capToCaseLimits({ maxDocumentsPerIntake: 1, maxPdfPages: 120 }), { maxDocumentsPerIntake: 1, maxPdfPages: 120 });
  assert.deepEqual(capToCaseLimits({ maxDocumentsPerIntake: undefined, maxPdfPages: undefined }), { maxDocumentsPerIntake: undefined, maxPdfPages: undefined });
});
