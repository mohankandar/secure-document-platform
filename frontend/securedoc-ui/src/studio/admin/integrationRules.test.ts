import assert from 'node:assert/strict';
import test from 'node:test';
import {
  VIEWS, advancedSummary, ago, attentionLine, attentionTitle, connectionStatus, connectionTag, draftTestResult, everyText, failureReason,
  methodOf, pagesText, ruleSentence, slashParts, statusText, watchedFolderStatus,
} from './integrationRules.ts';

const NOW = Date.parse('2026-11-18T12:00:00Z');
const minsAgo = (m: number) => new Date(NOW - m * 60000).toISOString();

test('view names are the vocabulary words', () => {
  assert.deepEqual(VIEWS.map((v) => v.label), ['Receiving', 'Sending to SDV', 'Sending by barcode', 'All']);
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
  assert.equal(connectionTag({ ...base, purpose: 'SDV_DELIVERY', providerType: 'HTTP_API' }), 'Secure Document Vault');
  assert.equal(connectionTag({ ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'FILE_SHARE' }), 'File share');
  assert.equal(connectionTag({ ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'HTTP_API' }), 'Web address');
});

test('one plain status line per connection, with no codes', () => {
  const c = { enabled: true, healthStatus: 'HEALTHY', lastSuccessAt: minsAgo(14), lastFailureAt: null, lastFailureCategory: null, purpose: 'INGESTION_SOURCE' as const, providerType: 'HTTP_API' as const };
  assert.equal(statusText(connectionStatus(c, NOW)), 'Working · last collected 14 min ago');
  const failing = { ...c, healthStatus: 'DEGRADED', lastSuccessAt: minsAgo(60), lastFailureAt: minsAgo(5), lastFailureCategory: 'POLL_SOURCE_UNAVAILABLE' };
  assert.equal(statusText(connectionStatus(failing, NOW)), "Having problems · last collected 1 h ago · last try failed: couldn't reach it");
  assert.equal(connectionStatus(failing, NOW).tone, 'warn');
  // A failure older than the last success is not shown.
  assert.equal(statusText(connectionStatus({ ...failing, healthStatus: 'HEALTHY', lastFailureAt: minsAgo(120) }, NOW)), 'Working · last collected 1 h ago');
  assert.equal(statusText(connectionStatus({ ...c, enabled: false }, NOW)), 'Switched off');
  assert.equal(statusText(connectionStatus({ ...c, purpose: 'SDV_DELIVERY', providerType: 'HTTP_API' }, NOW)), 'Working · last sent 14 min ago');
  assert.equal(connectionStatus({ ...c, providerType: 'PUSH_API', healthStatus: 'UNKNOWN', lastSuccessAt: null }, NOW).word, 'Ready to receive');
  assert.equal(connectionStatus({ ...c, purpose: 'SDV_DELIVERY', healthStatus: 'UNKNOWN', lastSuccessAt: null }, NOW).word, 'Not checked yet');
  assert.equal(connectionStatus({ ...c, healthStatus: 'SOMETHING_NEW' }, NOW).word, 'Status not recognised');
  // An unknown failure code is not shown or guessed at.
  const unknown = statusText(connectionStatus({ ...failing, lastFailureCategory: 'NEW_CODE_X' }, NOW));
  assert.doesNotMatch(unknown, /NEW_CODE_X/);
  assert.match(unknown, /reason not recognised/);
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
  assert.equal(attentionLine(item), "LN204812 · pages 3–4 → Title team folder · couldn't reach it · tried 5 times");
  assert.doesNotMatch(attentionLine({ ...item, primaryIdentifierValue: null }), /6f1c/);
  assert.equal(attentionTitle(1), "1 file couldn't be sent");
  assert.equal(attentionTitle(2), "2 files couldn't be sent");
});

test('the editor: method from a saved connection, and the advanced settings in words', () => {
  assert.equal(methodOf({ purpose: 'INGESTION_SOURCE', providerType: 'HTTP_API' }), 'POLL');
  assert.equal(methodOf({ purpose: 'DERIVED_DOCUMENT_DELIVERY', providerType: 'FILE_SHARE' }), 'BARCODE_SHARE');
  assert.equal(methodOf({ purpose: 'SDV_DELIVERY', providerType: 'HTTP_API' }), 'SDV');
  assert.equal(advancedSummary({ timeoutSeconds: 30, retryMaxAttempts: 5, incompleteWindowHours: 24, maxDocumentsPerIntake: 50, maxPdfSizeBytes: 50 * 1048576, maxPdfPages: 500, acknowledgementMode: 'SOURCE_PICKUP_ACK', purpose: 'INGESTION_SOURCE' }),
    'wait 30 s · try again up to 5 times · finish incomplete cases within 24 h · up to 50 files, 50 MB, 500 pages · confirm pickup to the source');
});

test('a test before saving reads as works or a plain reason, never a code', () => {
  assert.deepEqual(draftTestResult({ works: true, reasonCode: null }), { tone: 'ok', text: 'The connection works.' });
  assert.equal(draftTestResult({ works: false, reasonCode: 'DELIVERY_IO' }).text,
    "The connection didn't work: couldn't reach it. Check the address and sign-in, then test again.");
  assert.equal(draftTestResult({ works: false, reasonCode: 'ADDRESS_NOT_ALLOWED' }).text,
    "The connection didn't work: that address isn't allowed. Cloud metadata addresses are always refused.");
  assert.equal(draftTestResult({ works: false, reasonCode: 'ADDRESS_NOT_RESOLVED' }).text,
    "The connection didn't work: the address couldn't be found. Check the address and sign-in, then test again.");
  const unknown = draftTestResult({ works: false, reasonCode: 'SOMETHING_NEW' });
  assert.equal(unknown.text, "The connection didn't work. Check the address and sign-in, then test again.");
  assert.doesNotMatch(unknown.text, /SOMETHING_NEW/);
  assert.equal(failureReason('CONNECTION_TEST_FAILED'), 'the connection test failed');
  assert.equal(failureReason('SDV_TEST_UNAVAILABLE'), "SDV testing isn't set up in this installation");
});

test('an address wraps only after a slash, never inside "://" (U020)', () => {
  assert.deepEqual(slashParts('https://northwind.okta.com/oauth2/aus9lexa'), ['https://', 'northwind.okta.com/', 'oauth2/', 'aus9lexa']);
  assert.deepEqual(slashParts('\\\\server\\share\\in'), ['\\\\', 'server\\', 'share\\', 'in']);
  assert.deepEqual(slashParts('/data/inbox/'), ['/', 'data/', 'inbox/']);
  assert.deepEqual(slashParts('plain'), ['plain']);
  assert.deepEqual(slashParts(''), []);
});
