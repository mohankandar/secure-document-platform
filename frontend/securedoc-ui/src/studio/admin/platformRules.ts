/**
 * Admin › Platform operations in plain words (UI task U010, findings 26, 27, 28). Pure functions, so the words are
 * tested without a browser. Status words say only what the server can truly tell (owner's decision): it reports
 * working, degraded or failed, never "slow", so the words are Working / Having problems / Down. Codes, provider
 * names and node names stay under Technical details.
 */
import type {
  ProcessingCapacity,
  PlatformHealthItem,
  ProcessingStorageNodeValidation,
  ProcessingStorageOverview,
  ProcessingStorageProvider,
  ProductTelemetryStatus,
} from '../../types';

export type Tone = 'ok' | 'warn' | 'danger' | 'off' | 'neutral';
export type StatusLine = { tone: Tone; word: string; rest: string[] };

export const SECTION_LABELS = {
  overview: 'Overview',
  storage: 'Processing storage',
  capacity: 'Workers',
  branding: 'Branding',
  telemetry: 'Usage data',
} as const;

/* ---------- Workers (task 0067) ---------- */

const STAGE_NAMES: Record<string, string> = { TEXT: 'Read the text', FIND: 'Find', VISION: 'Look at pages', REDACT: 'Redact', EXPORT: 'Export' };
export const stageName = (stage: string): string => STAGE_NAMES[stage] ?? stage;

/** "AI server 1: 5 at once (learned)". */
export const aiServerText = (a: ProcessingCapacity['aiServers'][number]): string =>
  `AI server ${a.number}: ${a.requestsAtOnce} at once${a.learned ? ' (learned)' : ''}`;

export function capacityNote(c: Pick<ProcessingCapacity, 'intakeStopped' | 'intakeSlowdown' | 'staged'>): { tone: 'danger' | 'neutral'; text: string } | null {
  if (c.intakeStopped) return { tone: 'danger', text: 'LexaVault takes nothing in: too little disk or database space is free. It starts again by itself when there is enough. Senders are told to send again later; nothing is lost. · LV-INT-027' };
  if (c.intakeSlowdown > 1) return { tone: 'neutral', text: 'Many documents are waiting, so the watched folder and polling are checked less often. The files wait where they are.' };
  return null;
}

export const maximumError = (value: string): boolean => {
  const v = value.trim();
  return v !== '' && !(/^\d{1,2}$/.test(v) && Number(v) >= 1 && Number(v) <= 64);
};

/** Only what differs; an emptied maximum is null (LexaVault decides again). */
export function maximumChanges(saved: Record<string, number | null>, edits: Record<string, string>): Record<string, number | null> {
  return Object.fromEntries(Object.entries(edits).map(([k, v]) => [k, v.trim() === '' ? null : Number(v)] as const).filter(([k, v]) => (saved[k] ?? null) !== v));
}

/* ---------- Overview: services ---------- */

/** The service in plain words: "Text recognition", "Delivery to the document store". */
export function serviceName(item: Pick<PlatformHealthItem, 'key' | 'label' | 'provider'>): string {
  switch (item.key) {
    case 'database': return 'Database';
    case 'ai': return 'AI model';
    case 'ocr': return 'Text recognition';
    case 'storage': return 'Processing storage';
    case 'delivery': return item.provider === 'local' ? 'Delivery to a local folder' : 'Delivery to the document store';
    default: return item.label;
  }
}

const PRODUCTS: Record<string, string> = {
  postgresql: 'PostgreSQL',
  tesseract: 'Tesseract',
  ollama: 'Ollama',
  'openai-compatible': 'OpenAI-compatible service',
  openai_compatible: 'OpenAI-compatible service',
  vllm: 'vLLM',
  heuristic: 'Built-in rules, no AI model',
  shared_filesystem: 'Shared network folder',
  local_filesystem: 'Folder on this server',
};

/** The product behind the service, when it is one people recognise ("Tesseract", "Ollama"); otherwise nothing. */
export const serviceProduct = (item: Pick<PlatformHealthItem, 'provider'>): string => PRODUCTS[(item.provider || '').toLowerCase()] ?? '';

/**
 * The server reports HEALTHY / UNHEALTHY from a check, the document store connection's own health (HEALTHY, DEGRADED,
 * UNAVAILABLE, DISABLED, UNKNOWN), or CONFIGURED / NOT_CONFIGURED when nothing was checked. Anything else is shown
 * as not recognised, never as working.
 */
export function serviceStatus(status: string): StatusLine {
  switch ((status || '').toUpperCase()) {
    case 'HEALTHY': return { tone: 'ok', word: 'Working', rest: [] };
    case 'DEGRADED': return { tone: 'warn', word: 'Having problems', rest: [] };
    case 'UNHEALTHY':
    case 'UNAVAILABLE': return { tone: 'danger', word: 'Down', rest: [] };
    case 'CONFIGURED': return { tone: 'neutral', word: 'Set up', rest: ['not checked yet'] };
    case 'UNKNOWN': return { tone: 'neutral', word: 'Not checked yet', rest: [] };
    case 'NOT_CONFIGURED': return { tone: 'warn', word: 'Not set up', rest: [] };
    case 'DISABLED': return { tone: 'off', word: 'Switched off', rest: [] };
    default: return { tone: 'warn', word: 'Status not recognised', rest: [] };
  }
}

/** Task 0026: the status line of a service, with the code when its check failed ("Down · LV-SYS-006"). */
export function serviceStatusText(status: string, lvCode?: string | null): string {
  const s = serviceStatus(status);
  return [s.word, ...s.rest, ...(lvCode ? [lvCode] : [])].join(' · ');
}

export const needsAttention = (s: StatusLine) => s.tone === 'warn' || s.tone === 'danger';

/** The card title: "All services working", "1 service needs attention", "No problems found · 2 not checked yet". */
export function servicesTitle(statuses: readonly string[]): string {
  const lines = statuses.map(serviceStatus);
  const attention = lines.filter(needsAttention).length;
  if (attention) return `${attention} ${attention === 1 ? 'service needs' : 'services need'} attention`;
  const unchecked = lines.filter((l) => l.tone === 'neutral').length;
  if (unchecked) return `No problems found · ${unchecked} not checked yet`;
  return 'All services working';
}

/* ---------- Overview: watched folder ---------- */

/** "every 30 s", "every minute", "every 5 min", "every hour". */
export function intervalText(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `every ${s} s`;
  const m = Math.round(s / 60);
  if (m === 1) return 'every minute';
  if (m < 60) return `every ${m} min`;
  const h = Math.round(m / 60);
  return h === 1 ? 'every hour' : `every ${h} h`;
}

/* ---------- Processing storage ---------- */

const STORAGE_REASONS: Record<string, string> = {
  ROOT_NOT_FOUND: "the folder doesn't exist",
  ROOT_NOT_DIRECTORY: "the path isn't a folder",
  ROOT_NOT_READABLE: "can't read here",
  ROOT_NOT_WRITABLE: "can't write here",
  PROBE_IO_FAILED: "a test file couldn't be written or removed",
  PROBE_READ_MISMATCH: 'a test file came back different',
  PROBE_PATH_REJECTED: "the path isn't allowed",
  INVALID_PATH: "the path isn't allowed",
  TEST_FAILURE: 'the test failed',
};
export const storageReason = (code?: string | null) => STORAGE_REASONS[(code || '').toUpperCase()] ?? 'the test failed';

export const storageKind = (type: string) =>
  type === 'SHARED_FILESYSTEM' ? 'Shared network folder' : type === 'LOCAL_FILESYSTEM' ? 'Folder on this server' : type;

/** The latest check from each running server (node and process), so an old failure doesn't outlive a new success. */
export function latestChecks(validations: readonly ProcessingStorageNodeValidation[], providerId: string): ProcessingStorageNodeValidation[] {
  const latest = new Map<string, ProcessingStorageNodeValidation>();
  for (const v of validations) {
    if (v.providerId !== providerId) continue;
    const key = `${v.nodeId}:${v.processInstanceId}`;
    const seen = latest.get(key);
    if (!seen || new Date(v.checkedAt).getTime() > new Date(seen.checkedAt).getTime()) latest.set(key, v);
  }
  return [...latest.values()];
}

/** Same rule the server uses before it lets a storage become the default: healthy on every running server. */
export function storageValidated(o: Pick<ProcessingStorageOverview, 'nodeValidations' | 'activeNodeCount'>, providerId: string): boolean {
  const healthy = latestChecks(o.nodeValidations, providerId).filter((v) => v.status === 'HEALTHY').length;
  return o.activeNodeCount >= 1 && healthy >= o.activeNodeCount;
}

const servers = (n: number) => `${n} server${n === 1 ? '' : 's'}`;

/** "Working", "Not working from 1 of 2 servers · can't write here", "Not tested yet", "No longer used". */
export function storageStatus(p: Pick<ProcessingStorageProvider, 'id' | 'status' | 'providerType'>, o: Pick<ProcessingStorageOverview, 'nodeValidations' | 'activeNodeCount'>): StatusLine {
  if (p.status === 'RETIRED') return { tone: 'off', word: 'No longer used', rest: [] };
  const n = o.activeNodeCount;
  if (n < 1) return { tone: 'warn', word: 'No server has checked in', rest: [] };
  if (p.providerType === 'LOCAL_FILESYSTEM' && n > 1) {
    return { tone: 'danger', word: "Can't be used", rest: [`a folder on one server can't be shared by ${servers(n)}`] };
  }
  const checks = latestChecks(o.nodeValidations, p.id);
  const failing = checks.filter((v) => v.status !== 'HEALTHY');
  const healthy = checks.length - failing.length;
  if (failing.length) {
    return { tone: 'danger', word: n === 1 ? 'Not working' : `Not working from ${failing.length} of ${servers(n)}`, rest: [storageReason(failing[0].failureCode)] };
  }
  if (healthy >= n) return { tone: 'ok', word: 'Working', rest: [] };
  if (healthy === 0) return { tone: 'warn', word: 'Not tested yet', rest: [] };
  return { tone: 'warn', word: `Tested on ${healthy} of ${servers(n)}`, rest: [] };
}

/** Who uses it: "Default for every customer", "Northwind Commercial only", "Not used yet". */
export function storageUsedBy(p: Pick<ProcessingStorageProvider, 'id' | 'status'>, o: Pick<ProcessingStorageOverview, 'deploymentDefault' | 'tenantOverrides' | 'tenants'>): string {
  if (p.status === 'RETIRED') return 'Not used for new work';
  const isDefault = o.deploymentDefault?.providerId === p.id;
  const own = o.tenantOverrides.filter((a) => a.providerId === p.id).map((a) => o.tenants.find((t) => t.tenantId === a.tenantId)?.name ?? 'One customer');
  const parts: string[] = [];
  if (isDefault) parts.push(o.tenantOverrides.some((a) => a.providerId !== p.id) ? 'Default for everyone else' : 'Default for every customer');
  if (own.length) parts.push(`${own.join(', ')} only`);
  return parts.length ? parts.join(' · ') : 'Not used yet';
}

/** The readiness line above the table: "Ready · 1 server" or why documents can't be processed. */
export function storageReadiness(o: ProcessingStorageOverview): { ready: boolean; title: string; note: string } {
  if (o.ready) return { ready: true, title: `Ready · ${servers(o.activeNodeCount)}`, note: 'Documents are worked on here while they are redacted. Delivered copies go to the document store, not here.' };
  const def = o.providers.find((p) => p.id === o.deploymentDefault?.providerId);
  if (!def) return { ready: false, title: 'Not ready · no default storage', note: "Documents can't be taken in or processed until a storage is added, tested from every server and made the default." };
  if (def.providerType === 'LOCAL_FILESYSTEM' && o.activeNodeCount > 1) {
    return { ready: false, title: `Not ready · ${servers(o.activeNodeCount)} running`, note: `The default is a folder on one server, which ${servers(o.activeNodeCount)} can't share. Make a shared network folder the default.` };
  }
  return { ready: false, title: 'Not ready', note: `Documents can't be processed until the default storage works from every server (${servers(o.activeNodeCount)} running).` };
}

/* ---------- Usage data ---------- */

export function usageMode(mode: string): { known: boolean; word: string; note: string } {
  switch ((mode || '').toUpperCase()) {
    case 'OFF': return { known: true, word: 'Off', note: 'Nothing is sent.' };
    case 'BASIC': return { known: true, word: 'Basic', note: 'Anonymous counts for this installation and each customer.' };
    case 'ENHANCED': return { known: true, word: 'Detailed', note: 'As Basic, plus an anonymous case reference, so counts for one case can be linked together.' };
    default: return { known: false, word: 'Not recognised', note: 'The server reported a mode LexaVault does not know. See Technical details.' };
  }
}

export const USAGE_MODES = ['Off', 'Basic', 'Detailed'] as const;

const EVENTS: Record<string, string> = {
  'package.processing.attempt': 'a case started processing',
  'package.processing.failure': 'processing failed',
  'package.processing.finished': 'a case finished processing',
  'package.processing.recovery_exhausted': 'a case ran out of retries',
  'package.retention.purged': 'a kept copy was deleted on schedule',
  'package.technical_attention.action': 'a stopped case was handled',
  'package.technical_attention.expired': "a stopped case's waiting time ran out",
};
/** Each event in plain words; an event the UI doesn't know is shown as "another event" (its code is in Technical details). */
export const eventText = (code: string) => EVENTS[code] ?? 'another event';

const NEVER: [RegExp, string][] = [
  [/document\/pdf\/image/i, 'Document content: PDFs and images'],
  [/ocr|extracted text/i, 'Text read from documents'],
  [/business identifiers|names, addresses/i, 'Case values: loan numbers, names, addresses, account numbers'],
  [/prompts|model responses/i, 'What the AI model was asked or answered'],
  [/filenames|filesystem paths/i, 'File names and folder paths'],
  [/actor identities/i, 'Who did what: user names'],
];
/** Each never-sent kind of data in plain words; anything else is shown as the server wrote it. */
export const neverText = (s: string) => NEVER.find(([re]) => re.test(s))?.[1] ?? s;

export function usageSummary(t: Pick<ProductTelemetryStatus, 'mode' | 'enabled' | 'eventAllowList' | 'prohibitedData'>): { sends: string; never: string[] } {
  const off = (t.mode || '').toUpperCase() === 'OFF' || !t.enabled;
  const events = [...new Set(t.eventAllowList.map(eventText))];
  const sends = off ? 'Nothing is being sent.' : `Sends counts of when ${events.join(', ')}.`;
  return { sends, never: t.prohibitedData.map(neverText) };
}
