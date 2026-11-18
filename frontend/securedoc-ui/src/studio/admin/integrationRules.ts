/**
 * Admin › Integrations in plain words (UI task U008, findings 16, 24, 25). Pure functions, so the words are tested
 * without a browser. Codes (provider, authentication, failure category) never reach the main view; they stay under
 * Technical details.
 */
import type {
  DerivedDelivery,
  IntegrationAcknowledgementMode,
  IntegrationAuthenticationType,
  IntegrationConnection,
  IntegrationProviderType,
  IntegrationPurpose,
  RoutingMarkerType,
  RoutingScanRegion,
  WatchedFolderAdminStatus,
} from '../../types';

export type View = IntegrationPurpose | 'ALL';

export const VIEWS: { id: View; label: string; title: string; note: string; empty: string }[] = [
  { id: 'INGESTION_SOURCE', label: 'Receiving', title: 'Receiving documents', note: 'Systems, folders and scanners that bring documents into LexaVault.', empty: 'Nothing is set up to receive documents yet.' },
  { id: 'SDV_DELIVERY', label: 'Sending to SDV', title: 'Sending to SDV', note: 'Where the full redacted case goes when it is delivered.', empty: 'Nothing is set up to send to SDV yet.' },
  { id: 'DERIVED_DOCUMENT_DELIVERY', label: 'Sending by barcode', title: 'Sending by barcode', note: 'Pages with a barcode or text marker are also sent, as separate files, to the place their rule names. The full redacted case still goes to SDV.', empty: 'No places to send pages by barcode yet.' },
  { id: 'ALL', label: 'All', title: 'All connections', note: 'Every connection, receiving and sending.', empty: 'No connections are set up yet.' },
];

/* ---------- Time ---------- */

/** "just now", "14 min ago", "1 h ago", "3 days ago". */
export function ago(iso: string, now: number): string {
  const mins = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

/** "15 min", "hour", "2 h", "day": for "every …". */
export function everyText(minutes: number): string {
  if (minutes % 1440 === 0) return minutes === 1440 ? 'day' : `${minutes / 1440} days`;
  if (minutes % 60 === 0) return minutes === 60 ? 'hour' : `${minutes / 60} h`;
  return `${minutes} min`;
}

/* ---------- What a connection does ---------- */

export const isPolling = (c: Pick<IntegrationConnection, 'purpose' | 'providerType'>) =>
  c.purpose === 'INGESTION_SOURCE' && c.providerType === 'HTTP_API';

/** The one tag beside a connection's name. */
export function connectionTag(c: Pick<IntegrationConnection, 'purpose' | 'providerType' | 'pollingScheduleType' | 'pollingIntervalMinutes' | 'pollingLocalTime'>): string {
  if (c.purpose === 'INGESTION_SOURCE') {
    if (c.providerType === 'PUSH_API') return 'Sends to LexaVault';
    if (c.providerType === 'HTTP_API') {
      if (c.pollingScheduleType === 'DAILY') return `LexaVault collects daily${c.pollingLocalTime ? ` at ${c.pollingLocalTime}` : ''}`;
      return c.pollingIntervalMinutes ? `LexaVault collects every ${everyText(c.pollingIntervalMinutes)}` : 'LexaVault collects';
    }
    if (c.providerType === 'WATCHED_FOLDER') return 'Folder';
    if (c.providerType === 'SCANNER') return 'Scanner';
  }
  if (c.purpose === 'SDV_DELIVERY') return 'Secure Document Vault';
  if (c.providerType === 'FILE_SHARE') return 'File share';
  if (c.providerType === 'HTTP_API') return 'Web address';
  return 'Connection';
}

/* ---------- Status line ---------- */

/** A failure category in plain words. An unknown code is not guessed at; its code stays under Technical details. */
export function failureReason(code: string | null | undefined): string {
  switch ((code ?? '').trim().toUpperCase()) {
    case 'POLL_SOURCE_UNAVAILABLE':
    case 'DELIVERY_IO':
    case 'ROUTING_DESTINATION_UNAVAILABLE':
      return "couldn't reach it";
    case 'POLL_AUTH_CONFIGURATION_ERROR':
    case 'POLL_AUTH_CONFIGURATION_UNSUPPORTED':
      return 'its sign-in settings don’t work';
    case 'POLL_CONFIGURATION_ERROR':
    case 'DELIVERY_DESTINATION_INVALID':
      return 'its settings are not right';
    case 'POLL_RESPONSE_INVALID':
    case 'RETRIEVAL_INVALID_CONTENT_TYPE':
      return "it answered with something LexaVault can't read";
    case 'POLL_DISCOVERY_FAILED':
      return "the list of new documents couldn't be read";
    case 'RETRIEVAL_FAILED':
      return "documents couldn't be collected";
    case 'RETRIEVAL_STAGING_FAILED':
    case 'STORAGE_IO':
      return "LexaVault couldn't store the documents";
    case 'SOURCE_ACKNOWLEDGEMENT_FAILED':
    case 'ACK_SERIALIZATION_FAILED':
      return "pickup couldn't be confirmed to the source";
    case 'POLL_INTERRUPTED':
      return 'collecting was interrupted';
    case 'SDV_CONNECTION_TEST_FAILED':
    case 'DERIVED_CONNECTION_TEST_FAILED':
    case 'CONNECTION_TEST_FAILED':
      return 'the connection test failed';
    case 'ADDRESS_NOT_ALLOWED':
      return "that address isn't allowed";
    case 'ADDRESS_NOT_RESOLVED':
      return "the address couldn't be found";
    case 'SDV_TEST_UNAVAILABLE':
      return "SDV testing isn't set up in this installation";
    case 'DERIVED_DESTINATION_DISABLED':
      return 'the place it goes to is switched off';
    case 'ROUTING_MARKER_AMBIGUOUS':
      return 'the page matched more than one rule';
    case 'ROUTING_DETECTION_FAILED':
      return "the barcode or marker couldn't be read";
    case 'DERIVED_DELIVERY_RUNTIME':
    case 'UNKNOWN_TECHNICAL_FAILURE':
      return 'something unexpected went wrong';
    default:
      return 'reason not recognised (see Technical details)';
  }
}

/**
 * The answer of a test before saving, in words: "The connection works." or
 * "The connection didn't work: couldn't reach it. Check the address and sign-in, then test again."
 * An unknown code is not guessed at.
 */
export function draftTestResult(r: { works: boolean; reasonCode: string | null }): { tone: 'ok' | 'danger'; text: string } {
  if (r.works) return { tone: 'ok', text: 'The connection works.' };
  const known = failureReason(r.reasonCode);
  const reason = known.startsWith('reason not recognised') ? '' : `: ${known}`;
  if (r.reasonCode === 'ADDRESS_NOT_ALLOWED') {
    return { tone: 'danger', text: `The connection didn't work${reason}. Cloud metadata addresses are always refused.` };
  }
  return { tone: 'danger', text: `The connection didn't work${reason}. Check the address and sign-in, then test again.` };
}

export type Tone = 'ok' | 'warn' | 'danger' | 'off';
export interface StatusLine { tone: Tone; word: string; rest: string[] }

/**
 * One plain status line: "Working · last worked 14 min ago", "Having problems · last try failed: couldn't reach it",
 * "Switched off". Health comes from the connection's last operation (a collection, a delivery or a test).
 */
export function connectionStatus(c: Pick<IntegrationConnection, 'enabled' | 'healthStatus' | 'lastSuccessAt' | 'lastFailureAt' | 'lastFailureCategory' | 'purpose' | 'providerType'>, now: number): StatusLine {
  if (!c.enabled) return { tone: 'off', word: 'Switched off', rest: [] };
  const rest: string[] = [];
  const polling = isPolling(c);
  const waitsToBeSentTo = c.purpose === 'INGESTION_SOURCE' && !polling;
  if (c.lastSuccessAt) rest.push(`${polling ? 'last collected' : c.purpose === 'INGESTION_SOURCE' ? 'last worked' : 'last sent'} ${ago(c.lastSuccessAt, now)}`);
  const failedLast = !!c.lastFailureAt && (!c.lastSuccessAt || new Date(c.lastFailureAt).getTime() > new Date(c.lastSuccessAt).getTime());
  if (failedLast) rest.push(`last try failed: ${failureReason(c.lastFailureCategory)}`);
  switch (c.healthStatus) {
    case 'HEALTHY': return { tone: 'ok', word: 'Working', rest };
    case 'DEGRADED': return { tone: 'warn', word: 'Having problems', rest };
    case 'UNAVAILABLE': return { tone: 'danger', word: 'Not reachable', rest };
    case 'DISABLED': return { tone: 'off', word: 'Switched off', rest };
    case 'UNKNOWN':
      // A system that sends to LexaVault is not checked by LexaVault; it is ready as soon as it is switched on.
      return waitsToBeSentTo ? { tone: 'ok', word: 'Ready to receive', rest } : { tone: 'warn', word: 'Not checked yet', rest };
    default: return { tone: 'warn', word: 'Status not recognised', rest };
  }
}

/** The watched folder's line: "Working · checked 1 min ago · 2 accepted, 1 waiting for its READY file". */
export function watchedFolderStatus(w: WatchedFolderAdminStatus, now: number): StatusLine {
  if (!w.enabled) return { tone: 'off', word: 'Switched off', rest: [] };
  const rest: string[] = [];
  rest.push(w.lastScanAt ? `checked ${ago(w.lastScanAt, now)}` : 'not checked since LexaVault started');
  const r = w.lastScanResult;
  if (r) {
    const counts = [`${r.accepted} accepted`];
    if (r.waitingForTrigger) counts.push(`${r.waitingForTrigger} waiting for ${r.waitingForTrigger === 1 ? 'its' : 'their'} ${w.triggerFileName || 'ready'} file`);
    if (r.rejected) counts.push(`${r.rejected} refused`);
    if (r.duplicates) counts.push(`${r.duplicates} already received`);
    rest.push(counts.join(', '));
  }
  const outcome = (w.lastScanOutcome ?? '').toUpperCase();
  const failed = outcome.includes('FAIL') || outcome.includes('ERROR');
  return { tone: failed ? 'warn' : 'ok', word: failed ? 'Having problems' : 'Working', rest };
}

export const statusText = (s: StatusLine) => [s.word, ...s.rest].join(' · ');

/* ---------- Sending by barcode ---------- */

const MARKER_WORDS: Record<RoutingMarkerType, string> = { BARCODE: 'barcode', QR_CODE: 'QR code', TEXT_PATTERN: 'text' };
const REGION_WORDS: Record<RoutingScanRegion, string> = { TOP_RIGHT: 'top right of the page', BOTTOM_LEFT: 'bottom left of the page', BOTH: 'top right or bottom left of the page' };

/**
 * "Title pages: barcode “TITLE-001”, top right of the page → Title team folder". A barcode or QR code must match the
 * value exactly; text is found anywhere in the region (PdfRoutingMarkerDetectionAdapter).
 */
export function ruleSentence(rule: { destinationLabel: string; markerType: RoutingMarkerType; markerValue: string; scanRegion: RoutingScanRegion }, destinationName: string | undefined): string {
  const marker = rule.markerType === 'TEXT_PATTERN' ? `text “${rule.markerValue}”` : `${MARKER_WORDS[rule.markerType] ?? 'marker'} “${rule.markerValue}”`;
  return `${rule.destinationLabel}: ${marker}, ${REGION_WORDS[rule.scanRegion] ?? 'anywhere on the page'} → ${destinationName ?? 'a place that no longer exists'}`;
}

/** "page 3", "pages 3–4", "pages 2, 5–7". */
export function pagesText(pages: number[]): string {
  if (!pages.length) return 'no pages';
  const sorted = [...new Set(pages)].sort((a, b) => a - b);
  const parts: string[] = [];
  let start = sorted[0];
  let prev = sorted[0];
  for (const p of [...sorted.slice(1), Number.NaN]) {
    if (p === prev + 1) { prev = p; continue; }
    parts.push(start === prev ? `${start}` : `${start}–${prev}`);
    start = p; prev = p;
  }
  return `${sorted.length === 1 ? 'page' : 'pages'} ${parts.join(', ')}`;
}

export type AttentionItem = DerivedDelivery;

/** "LN204812 · pages 3–4 → Title team folder · couldn't reach it · tried 5 times". The case by its main ID, never the package id. */
export function attentionLine(d: AttentionItem): string {
  const parts = [d.primaryIdentifierValue?.trim() || 'Case without a main ID', pagesText(d.pageNumbers)];
  if (d.destinationLabel && d.markerType) parts[1] += ` → ${d.destinationLabel}`;
  parts.push(failureReason(d.failureCategory));
  if (d.attemptCount > 0) parts.push(`tried ${d.attemptCount} time${d.attemptCount === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

export const attentionTitle = (n: number) => `${n} file${n === 1 ? '' : 's'} couldn't be sent`;

/* ---------- Connection editor ---------- */

export type Method = 'PUSH' | 'POLL' | 'FOLDER' | 'SCANNER' | 'SDV' | 'BARCODE_WEB' | 'BARCODE_SHARE';

export const RECEIVE_METHODS: [Method, string][] = [
  ['PUSH', 'A system sends them to LexaVault'],
  ['POLL', 'LexaVault collects them on a schedule'],
  ['FOLDER', 'From a watched folder'],
  ['SCANNER', 'From a scanner'],
];
export const SEND_METHODS: [Method, string][] = [
  ['SDV', 'To Secure Document Vault (the full redacted case)'],
  ['BARCODE_WEB', 'Pages by barcode, to a web address'],
  ['BARCODE_SHARE', 'Pages by barcode, to a file share'],
];

export function methodOf(d: { purpose: IntegrationPurpose; providerType: IntegrationProviderType }): Method {
  if (d.purpose === 'SDV_DELIVERY') return 'SDV';
  if (d.purpose === 'DERIVED_DOCUMENT_DELIVERY') return d.providerType === 'FILE_SHARE' ? 'BARCODE_SHARE' : 'BARCODE_WEB';
  if (d.providerType === 'HTTP_API') return 'POLL';
  if (d.providerType === 'WATCHED_FOLDER') return 'FOLDER';
  if (d.providerType === 'SCANNER') return 'SCANNER';
  return 'PUSH';
}

export const AUTH_NAMES: [IntegrationAuthenticationType, string][] = [
  ['NONE', 'No sign-in'],
  ['API_KEY', 'API key'],
  ['BASIC', 'User name and password'],
  ['OAUTH2_CLIENT_CREDENTIALS', 'OAuth client credentials'],
  ['JWT', 'Signed token (JWT)'],
  ['MTLS', 'Client certificate (mutual TLS)'],
];

export const ACK_NAMES: [IntegrationAcknowledgementMode, string][] = [
  ['NONE', "Don't confirm"],
  ['HTTP_SUCCESS', 'A successful answer is enough'],
  ['DESTINATION_RECEIPT', 'Wait for a receipt from the destination'],
  ['DURABLE_FILE_WRITE', 'Confirm once the file is safely written'],
  ['SOURCE_PICKUP_ACK', 'Confirm pickup to the source'],
  ['SDV_SUBMISSION_ACK', 'SDV confirms it received the case'],
];

export const authName = (a: string) => AUTH_NAMES.find(([k]) => k === a)?.[1] ?? a;
export const ackName = (a: string) => ACK_NAMES.find(([k]) => k === a)?.[1] ?? a;

/** "wait 30 s · try 5 times · finish incomplete cases within 24 h · up to 50 files, 50 MB, 500 pages · confirm pickup to the source" */
export function advancedSummary(d: { timeoutSeconds?: number; retryMaxAttempts?: number; incompleteWindowHours?: number; maxDocumentsPerIntake?: number; maxPdfSizeBytes?: number; maxPdfPages?: number; acknowledgementMode: IntegrationAcknowledgementMode; purpose: IntegrationPurpose }): string {
  const parts = [`wait ${d.timeoutSeconds ?? 60} s`, `try again up to ${d.retryMaxAttempts ?? 2} time${d.retryMaxAttempts === 1 ? '' : 's'}`];
  if (d.purpose === 'INGESTION_SOURCE') {
    parts.push(`finish incomplete cases within ${d.incompleteWindowHours ?? 24} h`);
    parts.push(`up to ${d.maxDocumentsPerIntake ?? 100} files, ${Math.round((d.maxPdfSizeBytes ?? 262144000) / 1048576)} MB, ${d.maxPdfPages ?? 2000} pages`);
  }
  parts.push(ackName(d.acknowledgementMode).toLowerCase());
  return parts.join(' · ');
}
