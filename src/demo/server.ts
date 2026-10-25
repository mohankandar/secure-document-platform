/* eslint-disable @typescript-eslint/no-explicit-any */
import { buildRedactedPdf, renderPagePng, toCsv } from './assets';
import { capabilitiesFor, ROLE_USERS } from './roles';
import {
  DEMO_TENANT_ID, DEMO_TENANT_NAME, DEMO_TENANT_SLUG, demoId, loadState, saveState, stageOf,
  type DemoPackage, type DemoState,
} from './state';
import { getDemoSettings } from './settings';

type Json = any;

interface Ctx {
  method: string;
  path: string;
  query: URLSearchParams;
  body: Json;
  form: FormData | null;
  roles: string[];
  caps: Set<string>;
  actor: string;
}

class HttpError extends Error {
  constructor(public status: number, message: string, public errorCode = 'DEMO_ERROR') { super(message); }
}

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const iso = (ms: number) => new Date(ms).toISOString();
const ROLE_STORAGE_KEY = 'lexavault.demo.role';
const transientFailures = new Set<string>();

const json = (data: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(data === undefined ? null : JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json', ...headers } });

function require(ctx: Ctx, ...anyOf: string[]) {
  if (!anyOf.some((cap) => ctx.caps.has(cap))) throw new HttpError(403, 'You do not have permission to perform this action.', 'FORBIDDEN');
}

function findPackage(state: DemoState, packageId: string): DemoPackage {
  const pkg = state.packages.find((p) => p.packageId === packageId);
  if (!pkg) throw new HttpError(404, 'Package not found.', 'NOT_FOUND');
  return pkg;
}

function audit(state: DemoState, ctx: Ctx, action: string, resourceType: string, resourceId: string, detail?: string, outcome = 'SUCCESS') {
  state.audit.unshift({ id: demoId(), actorId: ctx.actor, action, resourceType, resourceId, outcome, correlationId: demoId(), detail, occurredAt: iso(Date.now()) });
}

function touch(pkg: DemoPackage, status?: string) {
  if (status) pkg.status = status;
  pkg.updatedAt = iso(Date.now());
}

/* ---------- Simulated processing lifecycle ---------- */

function advanceProcessing(state: DemoState) {
  const now = Date.now();
  let changed = false;
  state.packages.forEach((pkg) => {
    if (pkg.status !== 'PROCESSING' || !pkg.processingCompleteAt || new Date(pkg.processingCompleteAt).getTime() > now) return;
    pkg.documents.forEach((doc) => { doc.status = 'PROCESSED'; });
    pkg.processingCompleteAt = null;
    const needsReview = !pkg.reprocessed && pkg.documents.length > 0 && (pkg.packageId.charCodeAt(9) + pkg.documents.length) % 2 === 0;
    if (needsReview) {
      const doc = pkg.documents[0];
      ['SSN', 'ACCOUNT_NUMBER'].forEach((entityType, f) => state.findings.push({
        findingId: `${pkg.packageId.slice(0, 8)}-n${f}-${Date.now().toString(36)}`, packageId: pkg.packageId, documentId: doc.documentId,
        fileName: doc.fileName || 'Document.pdf', entityType, confidence: 0.61 + f * 0.08, pageNumber: 1 + (f % doc.pageCount),
        boundingBox: { left: 0.18, top: 0.24 + f * 0.18, width: 0.3, height: 0.035 }, reason: 'LOW_CONFIDENCE', status: 'OPEN',
      }));
      touch(pkg, 'PENDING_REVIEW');
    } else {
      touch(pkg, state.businessAdmin.lifecyclePolicy.finalApprovalRequired ? 'PENDING_FINAL_APPROVAL' : 'COMPLETED');
      if ((pkg.status as string) === 'COMPLETED') markExported(state, pkg);
    }
    state.audit.unshift({ id: demoId(), actorId: 'system', action: 'PROCESSING_COMPLETED', resourceType: 'PACKAGE', resourceId: pkg.packageId, outcome: 'SUCCESS', correlationId: demoId(), detail: needsReview ? 'Findings routed to manual review' : 'No uncertain findings', occurredAt: iso(now) });
    changed = true;
  });
  if (changed) saveState();
}

function startProcessing(pkg: DemoPackage, seconds = 8) {
  pkg.documents.forEach((doc) => { doc.status = 'PROCESSING'; });
  pkg.processingCompleteAt = iso(Date.now() + seconds * 1000);
  pkg.failureReason = null;
  pkg.technicalHoldUntil = null;
  touch(pkg, 'PROCESSING');
}

function markExported(state: DemoState, pkg: DemoPackage) {
  pkg.successfulExportCycles += 1;
  pkg.lastExportedAt = iso(Date.now());
  pkg.finalApprovalGranted = true;
  pkg.documents.forEach((doc) => { doc.status = 'EXPORTED'; });
  touch(pkg, 'COMPLETED');
  state.audit.unshift({ id: demoId(), actorId: 'system', action: 'PACKAGE_EXPORTED', resourceType: 'PACKAGE', resourceId: pkg.packageId, outcome: 'SUCCESS', correlationId: demoId(), detail: 'Delivered to SharePoint · Underwriting', occurredAt: iso(Date.now()) });
}

/* ---------- Projections ---------- */

function identifierDefs(state: DemoState) {
  return [...state.businessAdmin.identifiers].sort((a, b) => a.displayOrder - b.displayOrder);
}

function businessIdentifiers(state: DemoState, pkg: DemoPackage) {
  return identifierDefs(state).filter((def) => pkg.identifiers[def.key]).map((def) => ({
    key: def.key, label: def.label, value: pkg.identifiers[def.key], primary: def.primary, searchable: def.searchable, displayable: def.displayable, displayOrder: def.displayOrder,
  }));
}

function packageSummary(state: DemoState, pkg: DemoPackage) {
  return {
    packageId: pkg.packageId, tenantId: DEMO_TENANT_ID, packageType: pkg.packageType, status: pkg.status,
    businessIdentifiers: businessIdentifiers(state, pkg), documentCount: pkg.documents.length, createdAt: pkg.createdAt, updatedAt: pkg.updatedAt,
  };
}

function lifecycle(state: DemoState, pkg: DemoPackage) {
  const policy = state.businessAdmin.lifecyclePolicy;
  const created = new Date(pkg.createdAt).getTime();
  const exported = pkg.lastExportedAt ? new Date(pkg.lastExportedAt).getTime() : null;
  return {
    packageId: pkg.packageId,
    successfulExportCycles: pkg.successfulExportCycles,
    maximumExportCycles: policy.maximumSuccessfulExportCycles,
    canReprocess: pkg.successfulExportCycles < policy.maximumSuccessfulExportCycles && ['COMPLETED', 'FAILED', 'PENDING_FINAL_APPROVAL'].includes(pkg.status),
    finalApprovalRequired: policy.finalApprovalRequired,
    finalApprovalGranted: pkg.finalApprovalGranted,
    lastExportedAt: pkg.lastExportedAt,
    originalDeleteAt: iso(created + (exported ? policy.originalRetentionHours : policy.neverExportedHoldHours) * HOUR),
    redactedDeleteAt: exported ? iso(exported + policy.redactedCacheHours * HOUR) : null,
    technicalHoldUntil: pkg.technicalHoldUntil,
    redactedPurgedAt: null, originalPurgedAt: null, purgedAt: null,
    lastDestinationType: exported ? 'SDV' : null,
  };
}

const ATTENTION: Record<string, string> = { NEEDS_REVIEW: 'REVIEW', NEEDS_APPROVAL: 'APPROVAL', TECHNICAL_ATTENTION: 'TECHNICAL', REJECTED: 'REJECTED' };

function dashboard(state: DemoState, ctx: Ctx) {
  require(ctx, 'DASHBOARD_ACCESS');
  const stage = ctx.query.get('stage');
  const search = (ctx.query.get('search') || '').trim().toLowerCase();
  const offset = Math.max(0, Number(ctx.query.get('offset') || 0));
  const limit = Math.min(200, Math.max(1, Number(ctx.query.get('limit') || 25)));
  const sort = ctx.query.get('sort') || 'receivedAt';
  const direction = ctx.query.get('direction') === 'asc' ? 1 : -1;

  const visible = state.packages.filter((p) => !p.hidden && stageOf(p));
  const overview = { total: visible.length, processing: 0, needsReview: 0, needsApproval: 0, exported: 0, technicalAttention: 0, rejected: 0 };
  const key: Record<string, keyof typeof overview> = { PROCESSING: 'processing', NEEDS_REVIEW: 'needsReview', NEEDS_APPROVAL: 'needsApproval', EXPORTED: 'exported', TECHNICAL_ATTENTION: 'technicalAttention', REJECTED: 'rejected' };
  visible.forEach((p) => { overview[key[stageOf(p) as string]] += 1; });

  let rows = visible.filter((p) => !stage || stage === 'ALL' || stageOf(p) === stage);
  if (search) rows = rows.filter((p) => p.packageId.toLowerCase().includes(search) || Object.values(p.identifiers).some((v) => v.toLowerCase().includes(search)) || p.documents.some((d) => (d.fileName || '').toLowerCase().includes(search)));
  const value = (p: DemoPackage): string | number => {
    switch (sort) {
      case 'receivedAt': case 'createdAt': case 'received': return p.createdAt;
      case 'updatedAt': case 'updated': return p.updatedAt;
      case 'stage': return stageOf(p) || '';
      case 'documents': case 'documentCount': return p.documents.length;
      case 'exports': return p.successfulExportCycles;
      case 'source': return p.packageType;
      default: return (p.identifiers[sort] || '').toLowerCase();
    }
  };
  rows = [...rows].sort((a, b) => { const x = value(a); const y = value(b); return (x < y ? -1 : x > y ? 1 : 0) * direction; });

  const defs = identifierDefs(state);
  const columns = state.businessAdmin.dashboardColumns.filter((c) => c.allowed).sort((a, b) => a.displayOrder - b.displayOrder).map((c) => ({
    key: c.kind === 'IDENTIFIER' ? `identifier:${c.key}` : SYSTEM_COLUMN_KEYS[c.key]?.key || c.key,
    label: SYSTEM_COLUMN_KEYS[c.key]?.label || c.label, kind: c.kind, primary: c.primary, searchable: defs.find((d) => d.key === c.key)?.searchable ?? false, defaultVisible: c.defaultVisible, displayOrder: c.displayOrder,
  }));
  const items = rows.slice(offset, offset + limit).map((p) => {
    const life = lifecycle(state, p);
    const stageValue = stageOf(p) as string;
    return {
      packageId: p.packageId, packageType: p.packageType, status: p.status, stage: stageValue, attention: ATTENTION[stageValue] || 'NONE',
      identifiers: defs.filter((d) => d.displayable && p.identifiers[d.key]).map((d) => ({ key: d.key, label: d.label, value: p.identifiers[d.key], primary: d.primary, displayOrder: d.displayOrder })),
      documentCount: p.documents.length, successfulExportCycles: p.successfulExportCycles, lastExportedAt: p.lastExportedAt,
      originalDeleteAt: life.originalDeleteAt, technicalHoldUntil: p.technicalHoldUntil, createdAt: p.createdAt, updatedAt: p.updatedAt,
    };
  });
  return { overview, columns, items, totalItems: rows.length, offset, limit };
}

const SYSTEM_COLUMN_KEYS: Record<string, { key: string; label?: string }> = {
  source: { key: 'intakeSource' },
  exports: { key: 'exportCycles' },
  updated: { key: 'lastUpdated' },
  retention: { key: 'exportStatus', label: 'Export status' },
};

const median = (values: number[]) => {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const p95 = (values: number[]) => (values.length ? [...values].sort((a, b) => a - b)[Math.min(values.length - 1, Math.floor(values.length * 0.95))] : 0);
const round = (n: number, d = 1) => Math.round(n * 10 ** d) / 10 ** d;
const rate = (part: number, whole: number) => (whole ? round((part / whole) * 100) : 0);

const SOURCE_FILTER: Record<string, string[]> = { MANUAL: ['MANUAL_UPLOAD'], API: ['API_SUBMISSION'], WATCHED_FOLDER: ['FOLDER_INTAKE'], SCANNER: ['SCANNER_INTAKE'] };

function reports(state: DemoState, ctx: Ctx) {
  require(ctx, 'REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW');
  const now = Date.now();
  // The production client sends ISO instants (exclusive `to`); older callers sent bare dates.
  const instant = (raw: string | null, suffix: string) => (raw ? new Date(raw.includes('T') ? raw : `${raw}${suffix}`).getTime() : NaN);
  const toParsed = instant(ctx.query.get('to'), 'T23:59:59');
  const to = Number.isFinite(toParsed) ? toParsed : now;
  const fromParsed = instant(ctx.query.get('from'), 'T00:00:00');
  const from = Number.isFinite(fromParsed) ? fromParsed : to - 30 * DAY;
  const span = Math.max(DAY, to - from);
  const source = ctx.query.get('source') || 'ALL';
  const outcome = ctx.query.get('outcome') || 'ALL';
  const matchesSource = (p: DemoPackage) => source === 'ALL' || (SOURCE_FILTER[source] || [source]).includes(p.packageType);
  const matchesOutcome = (p: DemoPackage) => outcome === 'ALL'
    || (outcome === 'DELIVERED' && p.status === 'COMPLETED')
    || (outcome === 'IN_PROGRESS' && ['SUBMITTED', 'PROCESSING', 'PENDING_REVIEW', 'IN_REVIEW', 'PENDING_FINAL_APPROVAL'].includes(p.status))
    || (outcome === 'ATTENTION' && ['FAILED', 'REJECTED'].includes(p.status))
    || p.status === outcome;
  const scoped = state.packages.filter((p) => matchesSource(p) && matchesOutcome(p));
  const inRange = (p: DemoPackage, a: number, b: number) => { const t = new Date(p.createdAt).getTime(); return t >= a && t <= b; };
  const current = scoped.filter((p) => inRange(p, from, to));
  const previous = scoped.filter((p) => inRange(p, from - span, from - 1));
  const delivered = (list: DemoPackage[]) => list.filter((p) => p.lastExportedAt);
  const turnaround = (p: DemoPackage) => (new Date(p.lastExportedAt as string).getTime() - new Date(p.createdAt).getTime()) / HOUR;
  const hours = delivered(current).map(turnaround);
  const pages = current.reduce((sum, p) => sum + p.documents.reduce((s, d) => s + d.pageCount, 0), 0);
  const documents = current.reduce((sum, p) => sum + p.documents.length, 0);
  const reviewed = current.filter((p) => p.reviewed || state.findings.some((f) => f.packageId === p.packageId)).length;
  const backlogList = scoped.filter((p) => ['SUBMITTED', 'PROCESSING', 'PENDING_REVIEW', 'IN_REVIEW', 'PENDING_FINAL_APPROVAL'].includes(p.status));
  const ages = backlogList.map((p) => (now - new Date(p.createdAt).getTime()) / HOUR);
  const attention = scoped.filter((p) => p.status === 'FAILED' || p.status === 'REJECTED').length;
  const count = (list: DemoPackage[], status: string[]) => list.filter((p) => status.includes(p.status)).length;
  const cfg = state.businessAdmin.reportingConfiguration;
  const withinTarget = hours.filter((h) => h * 60 <= cfg.deliveryTargetMinutes).length;
  const buckets = Math.min(31, Math.max(1, Math.round(span / DAY)));
  const trend = Array.from({ length: buckets }, (_, i) => {
    const start = from + i * (span / buckets);
    const end = start + span / buckets;
    return {
      bucket: new Date(start).toISOString().slice(0, 10),
      received: current.filter((p) => inRange(p, start, end - 1)).length,
      delivered: current.filter((p) => p.lastExportedAt && new Date(p.lastExportedAt).getTime() >= start && new Date(p.lastExportedAt).getTime() < end).length,
    };
  });
  const dimensions = cfg.dimensions.filter((d) => d.enabled).map((dim) => {
    const values = Array.from(new Set(current.map((p) => p.identifiers[dim.key]).filter(Boolean)));
    return {
      key: dim.key, label: dim.label,
      values: values.map((value) => {
        const group = current.filter((p) => p.identifiers[dim.key] === value);
        const groupDelivered = delivered(group);
        return { value, packages: group.length, delivered: groupDelivered.length, deliveryRate: rate(groupDelivered.length, group.length), medianTurnaroundHours: round(median(groupDelivered.map(turnaround))) };
      }).sort((a, b) => b.packages - a.packages),
    };
  });
  const straightThrough = current.length - reviewed;
  return {
    generatedAt: iso(now),
    period: { from: iso(from), to: iso(to), previousFrom: iso(from - span), previousTo: iso(from - 1), source, outcome },
    executive: {
      batches: current.length, previousBatches: previous.length, documents, pages,
      completed: count(current, ['COMPLETED']), previousCompleted: count(previous, ['COMPLETED']),
      delivered: delivered(current).length, previousDelivered: delivered(previous).length,
      periodIntakeDelivered: delivered(current).length, previousPeriodIntakeDelivered: delivered(previous).length,
      completionRate: rate(count(current, ['COMPLETED']), current.length), deliveryRate: rate(delivered(current).length, current.length),
      periodIntakeDeliveryRate: rate(delivered(current).length, current.length),
      medianDeliveryHours: round(median(hours)), p95DeliveryHours: round(p95(hours)),
      straightThroughRate: rate(straightThrough, current.length), reviewRate: rate(reviewed, current.length),
      currentBacklog: backlogList.length, currentAttention: attention, oldestBacklogHours: round(Math.max(0, ...ages)), trend,
    },
    intake: {
      totalBatches: current.length, manual: count(current, []) + current.filter((p) => p.packageType === 'MANUAL_UPLOAD').length,
      api: current.filter((p) => p.packageType === 'API_SUBMISSION').length, watchedFolder: current.filter((p) => p.packageType === 'FOLDER_INTAKE').length,
      scanner: current.filter((p) => p.packageType === 'SCANNER_INTAKE').length, waitingForTrigger: 1, duplicateObservations: 2, rejectedObservations: count(current, ['REJECTED']),
    },
    processing: {
      processing: count(scoped, ['SUBMITTED', 'PROCESSING']), needsReview: count(scoped, ['PENDING_REVIEW', 'IN_REVIEW']), needsApproval: count(scoped, ['PENDING_FINAL_APPROVAL']),
      completed: count(current, ['COMPLETED']), technicalAttention: count(scoped, ['FAILED']), rejected: count(scoped, ['REJECTED']),
    },
    export: { exportedPackages: delivered(current).length, successfulExportCycles: current.reduce((s, p) => s + p.successfulExportCycles, 0), pendingFinalApproval: count(scoped, ['PENDING_FINAL_APPROVAL']) },
    retention: { originalsDueWithin24Hours: 3, redactedCopiesDueWithin24Hours: 2, technicalHoldsDueWithin24Hours: scoped.filter((p) => p.technicalHoldUntil && new Date(p.technicalHoldUntil).getTime() - now < DAY).length, purgedPackages: 41, originalsOverdue: 0, redactedCopiesOverdue: 0 },
    timing: {
      medianTurnaroundHours: round(median(hours)), p95TurnaroundHours: round(p95(hours)), medianActiveProcessingHours: round(median(hours) * 0.35), p95ActiveProcessingHours: round(p95(hours) * 0.4),
      medianReviewWaitHours: 0.8, medianReviewHandlingHours: 0.2, turnaroundSamples: hours.length, activeProcessingSamples: hours.length, reviewWaitSamples: reviewed, reviewHandlingSamples: reviewed,
    },
    backlog: {
      underOneHour: ages.filter((h) => h < 1).length, oneToFourHours: ages.filter((h) => h >= 1 && h < 4).length, fourToTwentyFourHours: ages.filter((h) => h >= 4 && h < 24).length,
      oneToThreeDays: ages.filter((h) => h >= 24 && h < 72).length, overThreeDays: ages.filter((h) => h >= 72).length, oldestHours: round(Math.max(0, ...ages)),
    },
    usage: {
      packages: current.length, documents, pages, averageDocumentsPerPackage: round(current.length ? documents / current.length : 0), averagePagesPerPackage: round(current.length ? pages / current.length : 0),
      reviewedPackages: reviewed, straightThroughPackages: straightThrough, reprocessedPackages: current.filter((p) => p.reprocessed).length,
    },
    serviceLevel: {
      deliveryTargetMinutes: cfg.deliveryTargetMinutes, deliveryTargetPercent: cfg.deliveryTargetPercent, deliveryWithinTargetRate: rate(withinTarget, hours.length),
      deliveryEligible: hours.length, deliveryBreaches: hours.length - withinTarget, straightThroughTargetPercent: cfg.straightThroughTargetPercent,
      straightThroughActualPercent: rate(straightThrough, current.length), reviewWaitTargetMinutes: cfg.reviewWaitTargetMinutes, reviewWithinTargetRate: 88.4, reviewEligible: reviewed, reviewBreaches: Math.round(reviewed * 0.116),
    },
    dimensions,
  };
}

function reviewItem(f: DemoState['findings'][number]) {
  return { findingId: f.findingId, packageId: f.packageId, documentId: f.documentId, fileName: f.fileName, entityType: f.entityType, confidence: f.confidence, pageNumber: f.pageNumber, hasBoundingBox: !!f.boundingBox, boundingBox: f.boundingBox, reason: f.reason };
}

function resolveFinding(state: DemoState, ctx: Ctx, findingId: string, confirmed: boolean) {
  require(ctx, 'MANUAL_REVIEW');
  const finding = state.findings.find((f) => f.findingId === findingId);
  if (!finding) throw new HttpError(404, 'Review item not found.', 'NOT_FOUND');
  if (finding.status !== 'OPEN') throw new HttpError(409, 'This item was already resolved by another reviewer.', 'CONFLICT');
  const owner = findPackage(state, finding.packageId);
  if (owner.status !== 'PENDING_REVIEW' && owner.status !== 'IN_REVIEW') throw new HttpError(409, 'This case is no longer waiting for review.', 'CONFLICT');
  if (confirmed && !finding.boundingBox && !ctx.body?.boundingBox) throw new HttpError(400, 'Place the redaction on the page before confirming.', 'VALIDATION_FAILED');
  if (confirmed && ctx.body?.boundingBox) finding.boundingBox = ctx.body.boundingBox;
  finding.status = confirmed ? 'CONFIRMED' : 'DISMISSED';
  const pkg = findPackage(state, finding.packageId);
  pkg.reviewed = true;
  audit(state, ctx, confirmed ? 'REVIEW_FINDING_CONFIRMED' : 'REVIEW_FINDING_DISMISSED', 'FINDING', findingId, ctx.body?.reason || undefined);
  const remaining = state.findings.some((f) => f.packageId === pkg.packageId && f.status === 'OPEN');
  if (!remaining) touch(pkg, state.businessAdmin.lifecyclePolicy.finalApprovalRequired ? 'PENDING_FINAL_APPROVAL' : 'COMPLETED');
  if (pkg.status === 'COMPLETED' && !remaining) markExported(state, pkg);
  return { findingId, packageId: pkg.packageId, status: finding.status, processingResumed: !remaining };
}

function incidentSummary(i: DemoState['incidents'][number]) {
  const { safeSummary, artifactAvailability, technicalAttentionRetainedUntil, artifactsPurgedAt, processingFingerprint, safeMetadata, events, packageStatus, failureCategory, ...summary } = i as Json;
  void safeSummary; void artifactAvailability; void technicalAttentionRetainedUntil; void artifactsPurgedAt; void processingFingerprint; void safeMetadata; void events; void packageStatus; void failureCategory;
  return summary;
}

function platformOverview(state: DemoState) {
  const now = Date.now();
  const failed = state.packages.filter((p) => p.status === 'FAILED');
  const item = (key: string, label: string, category: string, provider: string, status = 'UP', detail = 'Responding normally') => ({ key, label, category, provider, status, configured: true, detail });
  return {
    generatedAt: iso(now),
    providers: [
      item('database', 'Metadata database', 'STORAGE', 'PostgreSQL 16'),
      item('object-store', 'Artifact vault', 'STORAGE', 'S3-compatible'),
      item('ocr', 'OCR engine', 'PROCESSING', 'Tesseract 5.4'),
      item('detector', 'Sensitive data detector', 'PROCESSING', 'nw-2026.09'),
      item('ai-secondary', 'Secondary AI review', 'PROCESSING', 'Gateway · structured review', 'DEGRADED', 'p95 latency above 4s in the last hour'),
      item('identity', 'Identity provider', 'SECURITY', 'Northwind Entra ID'),
      item('sdv', 'Secure delivery vault', 'DELIVERY', 'SharePoint · Underwriting'),
    ],
    watchedFolder: {
      enabled: true, schedulingEnabled: true, scanIntervalMs: 30000, rootPath: '\\\\files.northwind.local\\lexavault\\inbox', triggerFileName: 'READY.trigger', incompleteVisibilityMinutes: 30,
      acceptedBehavior: 'MOVE_TO_PROCESSING', rejectedBehavior: 'MOVE_TO_REJECTED', processingDirectory: '/mnt/lexavault/processing/folder', coordinationEnabled: true,
      nodeId: 'lv-worker-01', nodeInstanceId: 'p-81a2', scannerRole: 'LEADER', leaseOwner: 'lv-worker-01', fencingToken: 418, leaseAcquiredAt: iso(now - 6 * HOUR), leaseHeartbeatAt: iso(now - 12_000),
      leaseExpiresAt: iso(now + 48_000), takeoverCount: 2, lastScanAt: iso(now - 25_000), lastScanOutcome: 'SUCCESS', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 },
    },
    resilience: { maxAttempts: 5, initialBackoffMs: 2000, packageProcessingMaxAttempts: 3, aiSecondaryEnabled: true, aiSecondaryProvider: 'Vercel AI Gateway', aiSecondaryModel: 'structured-review-v2' },
    integrations: { deliveryType: 'SDV', sdvConfigured: true, identityMode: 'OIDC', productionIdentityConfigured: true },
    technicalExceptions: failed.map((p) => ({ packageId: p.packageId, reasonCode: p.failureReason || 'UNKNOWN', status: p.status, documentCount: p.documents.length, failedAt: p.updatedAt, technicalHoldUntil: p.technicalHoldUntil })),
  };
}

function effectiveBranding(state: DemoState) {
  const tenant = state.tenantBranding[DEMO_TENANT_ID];
  const base = state.brandingDeployment;
  const pick = tenant || base;
  return {
    tenantId: tenant ? DEMO_TENANT_ID : null,
    applicationName: pick?.applicationName || 'LexaVault', shortName: pick?.shortName || 'LexaVault', organizationName: pick?.organizationName ?? DEMO_TENANT_NAME,
    pageTitle: pick?.pageTitle || 'LexaVault', accentColor: pick?.accentColor || '#2563eb', secondaryColor: pick?.secondaryColor || '#0f766e',
    primaryLogoDataUrl: pick?.primaryLogoDataUrl ?? null, compactLogoDataUrl: pick?.compactLogoDataUrl ?? null, faviconDataUrl: pick?.faviconDataUrl ?? null,
  };
}

const ROUTINE_AUDIT_ACTIONS = new Set(['WATCHED_FOLDER_SCAN_TRIGGERED']);
const AUDIT_CATEGORY: Record<string, (r: { action: string; outcome: string; actorId: string }) => boolean> = {
  MEANINGFUL: () => true,
  SECURITY: (r) => /IDENTITY|AUTH|LOGIN|SESSION/.test(r.action),
  ADMINISTRATION: (r) => /INTEGRATION|ROUTING|POLICY|IDENTIFIER|BRANDING|LICENSE|STORAGE|TELEMETRY|INTELLIGENCE|RETENTION|IDENTITY/.test(r.action),
  DOCUMENTS: (r) => /DOCUMENT|PACKAGE|FINDING|REVIEW|MANUAL_FALLBACK|PROCESSING/.test(r.action),
  EXPORTS: (r) => /EXPORT|DOWNLOAD|DELIVER/.test(r.action),
  FAILURES_DENIED: (r) => r.outcome !== 'SUCCESS',
  SYSTEM: (r) => r.actorId === 'system' || /HEALTH_CHECK|WATCHED_FOLDER|TECHNICAL/.test(r.action),
};
const EXACT_AUDIT_KEYS = new Set(['action', 'outcome', 'resourceType', 'resourceId', 'actorId', 'correlationId']);

function filterAudit(state: DemoState, query: URLSearchParams) {
  const category = query.get('category') || 'MEANINGFUL';
  const includeRoutine = query.get('includeRoutineActivity') === 'true' || ROUTINE_AUDIT_ACTIONS.has(query.get('action') || '');
  const inCategory = AUDIT_CATEGORY[category] || AUDIT_CATEGORY.MEANINGFUL;
  return state.audit.filter((record) => {
    if (!includeRoutine && ROUTINE_AUDIT_ACTIONS.has(record.action)) return false;
    if (!inCategory(record)) return false;
    for (const [key, raw] of query.entries()) {
      if (!raw) continue;
      if (key === 'from' && record.occurredAt < raw) return false;
      if (key === 'to' && record.occurredAt > raw) return false;
      if (EXACT_AUDIT_KEYS.has(key) && String((record as Json)[key] ?? '').toLowerCase() !== raw.toLowerCase()) return false;
    }
    return true;
  });
}

/* ---------- Router ---------- */

type Handler = (state: DemoState, ctx: Ctx, params: string[]) => Promise<unknown> | unknown;
const routes: Array<[string, RegExp, Handler]> = [];
const on = (method: string, pattern: string, handler: Handler) => {
  routes.push([method, new RegExp(`^${pattern.replace(/:[a-zA-Z]+/g, '([^/]+)')}$`), handler]);
};

on('GET', '/me', (_s, ctx) => ({ username: ROLE_USERS[ctx.roles[0]] || 'demo.user@northwind.example', roles: ctx.roles, tenantName: DEMO_TENANT_NAME, tenantSlug: DEMO_TENANT_SLUG, authenticationMode: 'LOCAL', capabilities: [...ctx.caps] }));
on('GET', '/branding/current', (s) => effectiveBranding(s));

on('GET', '/operations/dashboard', (s, ctx) => dashboard(s, ctx));
on('GET', '/operations/reports', (s, ctx) => reports(s, ctx));
on('GET', '/operations/reports/export', (s, ctx) => {
  require(ctx, 'REPORT_EXPORT');
  const r = reports(s, ctx) as Json;
  const rows = [
    ...Object.entries(r.executive).filter(([, v]) => typeof v === 'number').map(([metric, value]) => ({ section: 'executive', metric, value: value as number })),
    ...Object.entries(r.processing).map(([metric, value]) => ({ section: 'processing', metric, value: value as number })),
    ...Object.entries(r.backlog).map(([metric, value]) => ({ section: 'backlog', metric, value: value as number })),
  ];
  return new Response(toCsv(rows), { headers: { 'Content-Type': 'text/csv' } });
});

on('GET', '/packages', (s, ctx) => { require(ctx, 'DASHBOARD_ACCESS'); return s.packages.filter((p) => !p.hidden).map((p) => packageSummary(s, p)); });
on('POST', '/packages', (s, ctx) => {
  require(ctx, 'DOCUMENT_INTAKE');
  const ids = (ctx.body?.businessIdentifiers || {}) as Record<string, string>;
  const missing = identifierDefs(s).filter((d) => d.required && !String(ids[d.key] || '').trim());
  if (missing.length) throw new HttpError(400, `${missing.map((d) => d.label).join(', ')} is required.`, 'VALIDATION_FAILED');
  const now = iso(Date.now());
  const pkg: DemoPackage = {
    packageId: demoId(), packageType: ctx.body?.packageType || 'MANUAL_UPLOAD', status: 'DRAFT', identifiers: ids, documents: [], createdAt: now, updatedAt: now,
    successfulExportCycles: 0, lastExportedAt: null, finalApprovalGranted: false, technicalHoldUntil: null, hidden: false, reviewed: false, reprocessed: false,
    processingCompleteAt: null, failureReason: null, manualFallback: null,
  };
  s.packages.unshift(pkg);
  audit(s, ctx, 'PACKAGE_CREATED', 'PACKAGE', pkg.packageId);
  return { packageId: pkg.packageId, status: pkg.status };
});
on('GET', '/packages/:id', (s, ctx, [id]) => { require(ctx, 'DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW'); const p = findPackage(s, id); return { ...packageSummary(s, p), metadata: { source: p.packageType }, documents: p.documents }; });
on('GET', '/packages/:id/lifecycle', (s, ctx, [id]) => { require(ctx, 'DASHBOARD_ACCESS'); return lifecycle(s, findPackage(s, id)); });
on('POST', '/packages/:id/documents', (s, ctx, [id]) => {
  require(ctx, 'DOCUMENT_INTAKE');
  const pkg = findPackage(s, id);
  const file = ctx.form?.get('file');
  if (!(file instanceof File)) throw new HttpError(400, 'A PDF file is required.', 'VALIDATION_FAILED');
  if (pkg.status !== 'DRAFT' && pkg.status !== 'CREATED') throw new HttpError(409, 'Documents can only be added before the package is submitted.', 'INVALID_STATE');
  if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) throw new HttpError(415, `${file.name} is not a PDF.`, 'UNSUPPORTED_MEDIA_TYPE');
  // Demo scenario: a file whose name contains "interrupted" fails once per package, then succeeds on retry.
  const transientKey = `${id}:${file.name}`;
  if (/interrupted/i.test(file.name) && !transientFailures.has(transientKey)) {
    transientFailures.add(transientKey);
    throw new HttpError(503, `Upload of ${file.name} was interrupted. Retry to continue.`, 'SERVICE_UNAVAILABLE');
  }
  if (pkg.documents.some((d) => d.fileName === file.name && d.sizeBytes === file.size)) throw new HttpError(409, `${file.name} is already attached to this package.`, 'DUPLICATE_DOCUMENT');
  const documentId = demoId();
  pkg.documents.push({ documentId, fileName: file.name, mimeType: file.type || 'application/pdf', sizeBytes: file.size, documentType: 'PDF', status: 'UPLOADED', pageCount: Math.max(1, Math.min(40, Math.round(file.size / 90_000))) });
  touch(pkg);
  audit(s, ctx, 'DOCUMENT_UPLOADED', 'DOCUMENT', documentId, file.name);
  return { documentId, artifactId: demoId(), status: 'UPLOADED' };
});
on('POST', '/packages/:id/submit', (s, ctx, [id]) => {
  require(ctx, 'DOCUMENT_INTAKE');
  const pkg = findPackage(s, id);
  if (!pkg.documents.length) throw new HttpError(400, 'Add at least one document before submitting.', 'VALIDATION_FAILED');
  if (pkg.status !== 'DRAFT' && pkg.status !== 'CREATED') return { packageId: id, status: pkg.status };
  touch(pkg, 'SUBMITTED');
  audit(s, ctx, 'PACKAGE_SUBMITTED', 'PACKAGE', id);
  return { packageId: id, status: pkg.status };
});
on('POST', '/packages/:id/process', (s, ctx, [id]) => { require(ctx, 'DOCUMENT_INTAKE'); const pkg = findPackage(s, id); startProcessing(pkg); return { packageId: id, status: pkg.status }; });
on('POST', '/packages/:id/finalize', (s, ctx, [id]) => { require(ctx, 'MANUAL_REVIEW'); const pkg = findPackage(s, id); touch(pkg, 'PENDING_FINAL_APPROVAL'); return { packageId: id, status: pkg.status }; });
on('POST', '/packages/:id/reprocess', (s, ctx, [id]) => {
  require(ctx, 'PACKAGE_REPROCESS');
  const pkg = findPackage(s, id);
  if (!lifecycle(s, pkg).canReprocess) throw new HttpError(409, 'This package has reached its export-cycle limit or is not in a reprocessable state.', 'CONFLICT');
  pkg.reprocessed = true;
  pkg.finalApprovalGranted = false;
  startProcessing(pkg);
  audit(s, ctx, 'PACKAGE_REPROCESS_REQUESTED', 'PACKAGE', id);
  return undefined;
});
on('POST', '/packages/:id/approve-delivery', (s, ctx, [id]) => {
  require(ctx, 'PACKAGE_APPROVE_DELIVERY');
  const pkg = findPackage(s, id);
  if (pkg.status !== 'PENDING_FINAL_APPROVAL') throw new HttpError(409, 'Only packages awaiting final approval can be approved.', 'CONFLICT');
  audit(s, ctx, 'PACKAGE_DELIVERY_APPROVED', 'PACKAGE', id);
  markExported(s, pkg);
  return undefined;
});
on('POST', '/packages/:id/dashboard-hide', (s, ctx, [id]) => { require(ctx, 'PACKAGE_REMOVE_FROM_DASHBOARD'); const pkg = findPackage(s, id); pkg.hidden = true; audit(s, ctx, 'PACKAGE_HIDDEN_FROM_DASHBOARD', 'PACKAGE', id); return undefined; });
on('POST', '/packages/:id/technical-hold', (s, ctx, [id]) => {
  require(ctx, 'TECHNICAL_HOLD_MANAGE', 'TECHNICAL_RETRY');
  const pkg = findPackage(s, id);
  pkg.technicalHoldUntil = iso(Date.now() + Number(ctx.body?.holdHoursFromNow || 24) * HOUR);
  touch(pkg);
  audit(s, ctx, 'TECHNICAL_HOLD_EXTENDED', 'PACKAGE', id, `${ctx.body?.holdHoursFromNow}h`);
  return lifecycle(s, pkg);
});
on('GET', '/documents/:id/artifacts/redacted/content', (s, ctx, [id]) => {
  require(ctx, 'DASHBOARD_ACCESS');
  const doc = s.packages.flatMap((p) => p.documents).find((d) => d.documentId === id);
  if (!doc) throw new HttpError(404, 'Redacted artifact not found.', 'NOT_FOUND');
  return new Response(buildRedactedPdf(doc.fileName || 'document.pdf', doc.pageCount), { headers: { 'Content-Type': 'application/pdf' } });
});

on('GET', '/review-items', (s, ctx) => {
  require(ctx, 'MANUAL_REVIEW');
  const packageId = ctx.query.get('packageId');
  const limit = Number(ctx.query.get('limit') || 50);
  const open = s.findings.filter((f) => f.status === 'OPEN' && (!packageId || f.packageId === packageId) && s.packages.find((p) => p.packageId === f.packageId)?.status === 'PENDING_REVIEW');
  return { items: open.slice(0, limit).map(reviewItem), totalItems: open.length };
});
on('GET', '/review-items/:id/page-preview', async (s, ctx, [id]) => {
  require(ctx, 'MANUAL_REVIEW');
  const f = s.findings.find((x) => x.findingId === id);
  if (!f) throw new HttpError(404, 'Page preview unavailable.', 'NOT_FOUND');
  const doc = s.packages.flatMap((p) => p.documents).find((d) => d.documentId === f.documentId);
  const png = await renderPagePng({ title: f.fileName, pageNumber: f.pageNumber, pageCount: doc?.pageCount, seed: f.documentId, highlights: f.boundingBox ? [{ ...f.boundingBox, entityType: f.entityType }] : [] });
  return new Response(png, { headers: { 'Content-Type': 'image/png', 'X-Review-Page-Number': String(f.pageNumber) } });
});
on('POST', '/review-items/:id/confirm', (s, ctx, [id]) => resolveFinding(s, ctx, id, true));
on('POST', '/review-items/:id/dismiss', (s, ctx, [id]) => resolveFinding(s, ctx, id, false));
on('POST', '/findings/:id/confirm', (s, ctx, [id]) => { resolveFinding(s, ctx, id, true); return undefined; });
on('POST', '/findings/:id/dismiss', (s, ctx, [id]) => { resolveFinding(s, ctx, id, false); return undefined; });
on('POST', '/findings/:id/bounding-box', (s, ctx, [id]) => { require(ctx, 'MANUAL_REVIEW'); const f = s.findings.find((x) => x.findingId === id); if (f) f.boundingBox = { left: ctx.body.left, top: ctx.body.top, width: ctx.body.width, height: ctx.body.height }; return undefined; });
const reviewTask = (packageId: string, status: string, ctx: Ctx) => ({ reviewTaskId: `task-${packageId.slice(0, 8)}`, tenantId: DEMO_TENANT_ID, packageId, status, assignedTo: ctx.actor, createdAt: iso(Date.now()), updatedAt: iso(Date.now()) });
on('POST', '/review-tasks', (_s, ctx) => { require(ctx, 'MANUAL_REVIEW'); return reviewTask(ctx.body?.packageId || '', 'OPEN', ctx); });
on('POST', '/review-tasks/:id/assign', (_s, ctx, [id]) => reviewTask(id.replace('task-', ''), 'ASSIGNED', ctx));
on('POST', '/review-tasks/:id/start', (_s, ctx, [id]) => reviewTask(id.replace('task-', ''), 'IN_PROGRESS', ctx));
on('POST', '/review-tasks/:id/complete', (_s, ctx, [id]) => reviewTask(id.replace('task-', ''), 'COMPLETED', ctx));

const fallbackDetail = (s: DemoState, pkg: DemoPackage) => ({
  packageId: pkg.packageId, status: pkg.manualFallback?.status || 'NOT_STARTED',
  documents: pkg.documents.map((d) => ({ documentId: d.documentId, fileName: d.fileName || 'Document.pdf', pageCount: d.pageCount })),
  regions: pkg.manualFallback?.regions || [], enabledEntityTypes: s.businessAdmin.sensitiveDataPolicy.enabledEntityTypes,
});
on('POST', '/packages/:id/manual-fallback/start', (s, ctx, [id]) => {
  require(ctx, 'MANUAL_REVIEW');
  const pkg = findPackage(s, id);
  if (!pkg.manualFallback) pkg.manualFallback = { status: 'IN_PROGRESS', regions: [] };
  audit(s, ctx, 'MANUAL_FALLBACK_STARTED', 'PACKAGE', id);
  return fallbackDetail(s, pkg);
});
on('GET', '/packages/:id/manual-fallback', (s, ctx, [id]) => { require(ctx, 'MANUAL_REVIEW'); return fallbackDetail(s, findPackage(s, id)); });
on('GET', '/packages/:id/manual-fallback/documents/:doc/pages/:page/preview', async (s, ctx, [id, docId, page]) => {
  require(ctx, 'MANUAL_REVIEW');
  const doc = findPackage(s, id).documents.find((d) => d.documentId === docId);
  if (!doc) throw new HttpError(404, 'Document not found.', 'NOT_FOUND');
  const pageNumber = Math.min(doc.pageCount, Math.max(1, Number(page)));
  const png = await renderPagePng({ title: doc.fileName || 'Document', pageNumber, pageCount: doc.pageCount, seed: doc.documentId });
  return new Response(png, { headers: { 'Content-Type': 'image/png', 'X-Manual-Page-Number': String(pageNumber) } });
});
on('POST', '/packages/:id/manual-fallback/regions', (s, ctx, [id]) => {
  require(ctx, 'MANUAL_REVIEW');
  const pkg = findPackage(s, id);
  pkg.manualFallback = pkg.manualFallback || { status: 'IN_PROGRESS', regions: [] };
  const region = { findingId: demoId('mf'), documentId: ctx.body.documentId, pageNumber: ctx.body.pageNumber, entityType: ctx.body.entityType, ...ctx.body.boundingBox };
  pkg.manualFallback.regions.push(region);
  return region;
});
on('DELETE', '/packages/:id/manual-fallback/regions/:finding', (s, ctx, [id, findingId]) => {
  require(ctx, 'MANUAL_REVIEW');
  const pkg = findPackage(s, id);
  if (pkg.manualFallback) pkg.manualFallback.regions = pkg.manualFallback.regions.filter((r) => r.findingId !== findingId);
  return undefined;
});
on('POST', '/packages/:id/manual-fallback/submit', (s, ctx, [id]) => {
  require(ctx, 'MANUAL_REVIEW');
  const pkg = findPackage(s, id);
  if (!ctx.body?.reviewAttested) throw new HttpError(400, 'Confirm that every page was reviewed before submitting.', 'VALIDATION_FAILED');
  if (pkg.manualFallback) pkg.manualFallback.status = 'SUBMITTED';
  pkg.reviewed = true;
  pkg.failureReason = null;
  pkg.technicalHoldUntil = null;
  touch(pkg, 'PENDING_FINAL_APPROVAL');
  audit(s, ctx, 'MANUAL_FALLBACK_SUBMITTED', 'PACKAGE', id, `${pkg.manualFallback?.regions.length || 0} regions`);
  return { packageId: id, status: pkg.status };
});

on('GET', '/packages/:id/derived-deliveries', (s, ctx, [id]) => { require(ctx, 'DASHBOARD_ACCESS'); return s.deliveries.filter((d) => d.packageId === id); });
const deliver = (s: DemoState, ctx: Ctx, id: string) => {
  const d = s.deliveries.find((x) => x.derivedDeliveryId === id);
  if (!d) throw new HttpError(404, 'Delivery not found.', 'NOT_FOUND');
  Object.assign(d, { status: 'DELIVERED', attemptCount: d.attemptCount + 1, retryable: false, failureCategory: undefined, deliveredAt: iso(Date.now()), destinationReference: `REF-${Math.floor(Math.random() * 9000 + 1000)}` });
  audit(s, ctx, 'DERIVED_DELIVERY_SENT', 'DERIVED_DELIVERY', id, d.destinationLabel);
  return d;
};
on('POST', '/packages/:id/derived-deliveries/:d/send', (s, ctx, [, id]) => { require(ctx, 'PACKAGE_APPROVE_DELIVERY', 'TECHNICAL_RETRY'); return deliver(s, ctx, id); });
on('GET', '/derived-deliveries/attention', (s, ctx) => { require(ctx, 'TECHNICAL_EXCEPTION_VIEW', 'TENANT_INTEGRATION_MANAGE'); return s.deliveries.filter((d) => d.status !== 'DELIVERED'); });
on('POST', '/derived-deliveries/:id/technical-retry', (s, ctx, [id]) => { require(ctx, 'TECHNICAL_RETRY'); return deliver(s, ctx, id); });

on('GET', '/audit-records', (s, ctx) => { require(ctx, 'AUDIT_VIEW'); return filterAudit(s, ctx.query).slice(0, Number(ctx.query.get('limit') || 500)); });
on('GET', '/audit-records/export', (s, ctx) => {
  require(ctx, 'AUDIT_VIEW');
  const rows = filterAudit(s, ctx.query).map((r) => ({ occurredAt: r.occurredAt, actorId: r.actorId, action: r.action, resourceType: r.resourceType, resourceId: r.resourceId, outcome: r.outcome, detail: r.detail ?? '', correlationId: r.correlationId }));
  const format = ctx.query.get('format') || 'csv';
  return format === 'json'
    ? new Response(JSON.stringify(rows, null, 2), { headers: { 'Content-Type': 'application/json' } })
    : new Response(toCsv(rows), { headers: { 'Content-Type': 'text/csv' } });
});

on('GET', '/intake/identifier-definitions', (s, ctx) => { require(ctx, 'DOCUMENT_INTAKE', 'TENANT_IDENTIFIER_MANAGE'); return identifierDefs(s); });
on('GET', '/business-admin/configuration', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_IDENTIFIER_MANAGE'); return s.businessAdmin; });
const adminUpdate = (path: string, cap: string, apply: (s: DemoState, body: Json) => void, action: string) => on('PUT', path, (s, ctx) => {
  require(ctx, cap);
  apply(s, ctx.body);
  audit(s, ctx, action, 'TENANT_POLICY', DEMO_TENANT_ID);
  return s.businessAdmin;
});
adminUpdate('/business-admin/identifiers', 'TENANT_IDENTIFIER_MANAGE', (s, b) => {
  s.businessAdmin.identifiers = b.identifiers.map((d: Json, i: number) => ({ id: d.id || demoId('idf'), ...d, displayOrder: d.displayOrder ?? i + 1 }));
  const known = new Set(s.businessAdmin.dashboardColumns.map((c) => c.key));
  s.businessAdmin.identifiers.forEach((d) => { if (!known.has(d.key)) s.businessAdmin.dashboardColumns.push({ key: d.key, label: d.label, kind: 'IDENTIFIER', primary: d.primary, allowed: true, defaultVisible: false, displayOrder: s.businessAdmin.dashboardColumns.length + 1 }); });
}, 'IDENTIFIERS_UPDATED');
adminUpdate('/business-admin/dashboard-columns', 'TENANT_POLICY_MANAGE', (s, b) => {
  b.columns.forEach((c: Json) => { const col = s.businessAdmin.dashboardColumns.find((x) => x.key === c.key); if (col) Object.assign(col, { allowed: c.allowed, defaultVisible: c.defaultVisible }); });
}, 'DASHBOARD_COLUMNS_UPDATED');
adminUpdate('/business-admin/sensitive-data-policy', 'TENANT_POLICY_MANAGE', (s, b) => { s.businessAdmin.sensitiveDataPolicy = { ...s.businessAdmin.sensitiveDataPolicy, ...b }; }, 'POLICY_UPDATED');
adminUpdate('/business-admin/lifecycle-policy', 'TENANT_POLICY_MANAGE', (s, b) => { s.businessAdmin.lifecyclePolicy = { ...s.businessAdmin.lifecyclePolicy, ...b }; }, 'LIFECYCLE_POLICY_UPDATED');
adminUpdate('/business-admin/reporting-configuration', 'TENANT_POLICY_MANAGE', (s, b) => {
  const cfg = s.businessAdmin.reportingConfiguration;
  cfg.dimensions.forEach((d) => { d.enabled = (b.enabledDimensionDefinitionIds || []).includes(d.definitionId); });
  Object.assign(cfg, { deliveryTargetMinutes: b.deliveryTargetMinutes, deliveryTargetPercent: b.deliveryTargetPercent, straightThroughTargetPercent: b.straightThroughTargetPercent, reviewWaitTargetMinutes: b.reviewWaitTargetMinutes ?? cfg.reviewWaitTargetMinutes });
}, 'REPORTING_CONFIGURATION_UPDATED');
adminUpdate('/business-admin/document-intelligence', 'TENANT_POLICY_MANAGE', (s, b) => {
  const di = s.businessAdmin.documentIntelligence;
  Object.assign(di, { mode: b.mode ?? di.mode, activeBundleVersion: b.activeBundleVersion ?? di.activeBundleVersion, configurationVersion: di.configurationVersion + 1 });
}, 'DOCUMENT_INTELLIGENCE_UPDATED');
on('POST', '/business-admin/document-intelligence/rollback', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE'); const di = s.businessAdmin.documentIntelligence; di.activeBundleVersion = di.lastKnownGoodBundleVersion || di.activeBundleVersion; di.configurationVersion += 1; audit(s, ctx, 'DOCUMENT_INTELLIGENCE_ROLLED_BACK', 'TENANT_POLICY', DEMO_TENANT_ID); return s.businessAdmin; });
on('POST', '/business-admin/document-intelligence/last-known-good', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE'); const di = s.businessAdmin.documentIntelligence; di.lastKnownGoodBundleVersion = di.activeBundleVersion; return s.businessAdmin; });

const destinations = (s: DemoState) => s.integrations.filter((i) => i.purpose === 'DERIVED_DOCUMENT_DELIVERY').map((i) => ({ connectionId: i.connectionId, name: i.name, providerType: i.providerType, enabled: i.enabled, healthStatus: i.healthStatus }));
on('GET', '/business-admin/routing-rules', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE', 'DASHBOARD_ACCESS'); return s.routingRules; });
on('GET', '/business-admin/routing-rules/destinations', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE'); return destinations(s); });
on('POST', '/business-admin/routing-rules', (s, ctx) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE'); const rule = { ruleId: demoId('rr'), enabled: true, ...ctx.body }; s.routingRules.push(rule); audit(s, ctx, 'ROUTING_RULE_CREATED', 'ROUTING_RULE', rule.ruleId, rule.destinationLabel); return rule; });
on('PUT', '/business-admin/routing-rules/:id', (s, ctx, [id]) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE'); const rule = s.routingRules.find((r) => r.ruleId === id); if (!rule) throw new HttpError(404, 'Routing rule not found.'); Object.assign(rule, ctx.body); audit(s, ctx, 'ROUTING_RULE_UPDATED', 'ROUTING_RULE', id); return rule; });
on('DELETE', '/business-admin/routing-rules/:id', (s, ctx, [id]) => { require(ctx, 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE'); s.routingRules = s.routingRules.filter((r) => r.ruleId !== id); audit(s, ctx, 'ROUTING_RULE_DELETED', 'ROUTING_RULE', id); return undefined; });

const findIntegration = (s: DemoState, id: string) => { const i = s.integrations.find((x) => x.connectionId === id); if (!i) throw new HttpError(404, 'Connection not found.', 'NOT_FOUND'); return i; };
on('GET', '/integrations', (s, ctx) => { require(ctx, 'TENANT_INTEGRATION_MANAGE'); return s.integrations; });
on('POST', '/integrations', (s, ctx) => {
  require(ctx, 'TENANT_INTEGRATION_MANAGE');
  const now = iso(Date.now());
  const { secret, secretReference, ...rest } = ctx.body || {};
  const created = { ...s.integrations[0], ...rest, connectionId: demoId('int'), secretConfigured: !!(secret || secretReference), configurationVersion: 1, healthStatus: 'UNKNOWN', lastSuccessAt: null, lastFailureAt: null, lastFailureCategory: null, createdAt: now, updatedAt: now };
  s.integrations.push(created);
  audit(s, ctx, 'INTEGRATION_CREATED', 'INTEGRATION', created.connectionId, created.name);
  return created;
});
on('PUT', '/integrations/:id', (s, ctx, [id]) => {
  require(ctx, 'TENANT_INTEGRATION_MANAGE');
  const i = findIntegration(s, id);
  const { secret, secretReference, expectedConfigurationVersion, ...rest } = ctx.body || {};
  if (expectedConfigurationVersion !== undefined && expectedConfigurationVersion !== i.configurationVersion) throw new HttpError(409, 'This connection was changed by someone else. Reload and try again.', 'CONFLICT');
  Object.assign(i, rest, { secretConfigured: secret || secretReference ? true : i.secretConfigured, configurationVersion: i.configurationVersion + 1, updatedAt: iso(Date.now()) });
  audit(s, ctx, 'INTEGRATION_UPDATED', 'INTEGRATION', id, i.name);
  return i;
});
on('POST', '/integrations/:id/(enable|disable)', (s, ctx, [id, action]) => { require(ctx, 'TENANT_INTEGRATION_MANAGE'); const i = findIntegration(s, id); i.enabled = action === 'enable'; i.configurationVersion += 1; audit(s, ctx, action === 'enable' ? 'INTEGRATION_ENABLED' : 'INTEGRATION_DISABLED', 'INTEGRATION', id); return i; });
on('GET', '/integrations/:id/polling', (s, ctx, [id]) => {
  require(ctx, 'TENANT_INTEGRATION_MANAGE');
  const i = findIntegration(s, id);
  return { connectionId: id, name: i.name, enabled: i.enabled, healthStatus: i.healthStatus, nextScheduledAt: i.enabled ? iso(Date.now() + 11 * 60_000) : null, recentRuns: s.pollingRuns.filter((r) => r.connectionId === id) };
});
on('POST', '/integrations/:id/polling/run-now', (s, ctx, [id]) => {
  require(ctx, 'TENANT_INTEGRATION_MANAGE');
  const started = Date.now();
  const run = { runId: demoId('run'), connectionId: id, trigger: 'MANUAL', status: 'SUCCESS', startedAt: iso(started), completedAt: iso(started + 2400), packagesDiscovered: 1, documentsExpected: 3, documentsRetrieved: 3, packagesAcknowledged: 1, failureCategory: null, failureDetail: null } as DemoState['pollingRuns'][number];
  s.pollingRuns.unshift(run);
  audit(s, ctx, 'POLLING_RUN_TRIGGERED', 'INTEGRATION', id);
  return run;
});
const testConnection = (s: DemoState, ctx: Ctx, id: string) => {
  require(ctx, 'TENANT_INTEGRATION_MANAGE');
  const i = findIntegration(s, id);
  const healthy = i.enabled && i.healthStatus !== 'DEGRADED';
  if (healthy) i.lastSuccessAt = iso(Date.now());
  audit(s, ctx, 'INTEGRATION_TESTED', 'INTEGRATION', id, healthy ? 'Connection test passed' : 'Destination timed out', healthy ? 'SUCCESS' : 'FAILURE');
  return { connectionId: id, healthStatus: healthy ? 'HEALTHY' : 'DEGRADED', secretConfigured: i.secretConfigured, configurationVersion: i.configurationVersion };
};
on('POST', '/integrations/:id/sdv/test-connection', (s, ctx, [id]) => testConnection(s, ctx, id));
on('POST', '/integrations/:id/derived/test-connection', (s, ctx, [id]) => testConnection(s, ctx, id));

on('GET', '/admin/enterprise-identity', (s, ctx) => { require(ctx, 'TENANT_AUTH_MANAGE'); return s.identity; });
on('PUT', '/admin/enterprise-identity/draft', (s, ctx) => {
  require(ctx, 'TENANT_AUTH_MANAGE');
  const base = s.identity.staged || s.identity.active;
  const now = iso(Date.now());
  s.identity.staged = { ...(base as Json), ...ctx.body, connectionId: s.identity.staged?.connectionId || demoId('idp'), tenantId: DEMO_TENANT_ID, status: 'DRAFT', configurationVersion: (s.identity.active?.configurationVersion || 0) + 1, createdAt: s.identity.staged?.createdAt || now, updatedAt: now, lastTestedAt: null, lastTestStatus: null, lastTestMessage: null };
  audit(s, ctx, 'IDENTITY_DRAFT_SAVED', 'IDENTITY_PROVIDER', s.identity.staged!.connectionId);
  return s.identity;
});
on('POST', '/admin/enterprise-identity/draft/test', (s, ctx) => {
  require(ctx, 'TENANT_AUTH_MANAGE');
  const staged = s.identity.staged;
  if (!staged) throw new HttpError(409, 'Save a draft before testing.', 'CONFLICT');
  const passed = /^https:\/\//.test(staged.issuerUri);
  Object.assign(staged, { status: passed ? 'VALIDATED' : 'DRAFT', lastTestedAt: iso(Date.now()), lastTestStatus: passed ? 'PASSED' : 'FAILED', lastTestMessage: passed ? 'Discovery document and signing keys resolved.' : 'Issuer must be an HTTPS URL.' });
  audit(s, ctx, 'IDENTITY_DRAFT_TESTED', 'IDENTITY_PROVIDER', staged.connectionId, staged.lastTestMessage || undefined, passed ? 'SUCCESS' : 'FAILURE');
  return s.identity;
});
on('POST', '/admin/enterprise-identity/draft/activate', (s, ctx) => {
  require(ctx, 'TENANT_AUTH_MANAGE');
  const staged = s.identity.staged;
  if (ctx.body?.confirmation !== 'ACTIVATE') throw new HttpError(400, 'Activation requires explicit confirmation.', 'BAD_REQUEST');
  if (!staged || staged.connectionId !== ctx.body?.connectionId) throw new HttpError(409, 'The staged draft changed. Reload and review it before activating.', 'CONFLICT');
  if (staged.configurationVersion !== ctx.body?.expectedConfigurationVersion) throw new HttpError(409, `Version ${ctx.body?.expectedConfigurationVersion} is no longer the staged version. Reload and review it before activating.`, 'CONFLICT');
  if (staged.status !== 'VALIDATED' || staged.lastTestStatus !== 'PASSED') throw new HttpError(409, 'Only a VALIDATED draft with a passing connection test can be activated.', 'CONFLICT');
  if (!staged.lastTestedAt || Date.now() - new Date(staged.lastTestedAt).getTime() > s.identity.activationMaxTestAgeSeconds * 1000) throw new HttpError(409, 'The last passing connection test is too old. Test again before activating.', 'CONFLICT');
  if (s.identity.active) s.identity.rollbackCandidates = [{ ...s.identity.active, status: 'DISABLED' as const }, ...s.identity.rollbackCandidates].slice(0, 3);
  s.identity.active = { ...staged, status: 'ACTIVE', updatedAt: iso(Date.now()) };
  s.identity.staged = null;
  audit(s, ctx, 'IDENTITY_PROVIDER_ACTIVATED', 'IDENTITY_PROVIDER', s.identity.active.connectionId);
  return s.identity;
});
on('POST', '/admin/enterprise-identity/rollback', (s, ctx) => {
  require(ctx, 'TENANT_AUTH_MANAGE');
  const target = s.identity.rollbackCandidates.find((c) => c.connectionId === ctx.body?.connectionId);
  if (ctx.body?.confirmation !== 'ROLLBACK') throw new HttpError(400, 'Rollback requires explicit confirmation.', 'BAD_REQUEST');
  if (!target) throw new HttpError(404, 'Rollback target not found.', 'NOT_FOUND');
  if (target.configurationVersion !== ctx.body?.expectedConfigurationVersion) throw new HttpError(409, 'The rollback target changed. Reload and review it before rolling back.', 'CONFLICT');
  s.identity.rollbackCandidates = [{ ...(s.identity.active as Json), status: 'DISABLED' }, ...s.identity.rollbackCandidates.filter((c) => c !== target)];
  s.identity.active = { ...target, status: 'ACTIVE', updatedAt: iso(Date.now()) };
  audit(s, ctx, 'IDENTITY_PROVIDER_ROLLED_BACK', 'IDENTITY_PROVIDER', target.connectionId);
  return s.identity;
});

on('GET', '/platform-admin/overview', (s, ctx) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return platformOverview(s); });
on('POST', '/platform-admin/health-check', (s, ctx) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); audit(s, ctx, 'HEALTH_CHECK_RUN', 'PLATFORM', 'deployment'); return platformOverview(s); });
on('POST', '/platform-admin/watched-folder/scan', (s, ctx) => { require(ctx, 'PLATFORM_OPERATIONS'); audit(s, ctx, 'WATCHED_FOLDER_SCAN_TRIGGERED', 'PLATFORM', 'watched-folder'); return platformOverview(s).watchedFolder.lastScanResult; });
on('GET', '/platform/product-telemetry/status', (_s, ctx) => {
  require(ctx, 'TECHNICAL_ADMIN_VIEW');
  return { mode: 'BASIC', enabled: true, installationIdConfigured: true, privacyContract: 'lexavault-telemetry-v2', eventAllowList: ['INSTALLATION_HEARTBEAT', 'FEATURE_ENABLED', 'VERSION_REPORTED'], prohibitedData: ['Document content', 'Business identifiers', 'User names', 'IP addresses'] };
});
on('GET', '/platform/processing-storage', (s, ctx) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return s.storage; });
on('POST', '/platform/processing-storage/providers', (s, ctx) => { require(ctx, 'PLATFORM_OPERATIONS'); const p = { id: demoId('psp'), status: 'CANDIDATE', configurationVersion: 1, ...ctx.body }; s.storage.providers.push(p); audit(s, ctx, 'STORAGE_PROVIDER_CREATED', 'STORAGE_PROVIDER', p.id); return p; });
on('POST', '/platform/processing-storage/providers/:id/test', (s, ctx, [id]) => {
  require(ctx, 'PLATFORM_OPERATIONS');
  s.storage.nodeValidations = s.storage.nodeValidations.filter((v) => v.providerId !== id);
  ['lv-worker-01', 'lv-worker-02'].forEach((nodeId, i) => s.storage.nodeValidations.push({ nodeId, processInstanceId: `p-${i}${id.slice(-3)}`, providerId: id, status: 'HEALTHY', failureCode: null, checkedAt: iso(Date.now()) }));
  const p = s.storage.providers.find((x) => x.id === id); if (p && p.status === 'CANDIDATE') p.status = 'AVAILABLE';
  return s.storage;
});
on('POST', '/platform/processing-storage/providers/:id/activate-default', (s, ctx, [id]) => { require(ctx, 'PLATFORM_OPERATIONS'); s.storage.deploymentDefault = { id: demoId('asg'), tenantId: null, providerId: id, scope: 'DEPLOYMENT_DEFAULT', activatedAt: iso(Date.now()) }; audit(s, ctx, 'STORAGE_DEFAULT_ACTIVATED', 'STORAGE_PROVIDER', id); return s.storage.providers.find((p) => p.id === id); });
on('POST', '/platform/processing-storage/providers/:id/activate-tenant/:tenant', (s, ctx, [id, tenantId]) => { require(ctx, 'PLATFORM_OPERATIONS'); s.storage.tenantOverrides = [...s.storage.tenantOverrides.filter((o) => o.tenantId !== tenantId), { id: demoId('asg'), tenantId, providerId: id, scope: 'TENANT_OVERRIDE', activatedAt: iso(Date.now()) }]; return s.storage.providers.find((p) => p.id === id); });
on('DELETE', '/platform/processing-storage/tenant-overrides/:tenant', (s, ctx, [tenantId]) => { require(ctx, 'PLATFORM_OPERATIONS'); s.storage.tenantOverrides = s.storage.tenantOverrides.filter((o) => o.tenantId !== tenantId); return undefined; });
on('POST', '/platform/processing-storage/providers/:id/retire', (s, ctx, [id]) => {
  require(ctx, 'PLATFORM_OPERATIONS');
  if (s.storage.deploymentDefault?.providerId === id) throw new HttpError(409, 'Activate a different default provider before retiring this one.', 'CONFLICT');
  const p = s.storage.providers.find((x) => x.id === id); if (p) p.status = 'RETIRED'; return p;
});
on('GET', '/platform/licensing', (s, ctx) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return s.licensing; });
on('GET', '/platform/licensing/tenants/:id', (s, ctx, [id]) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); const t = s.licensing.tenants.find((x) => x.tenantId === id); if (!t) throw new HttpError(404, 'Tenant not found.'); return t; });
on('GET', '/platform/licensing/tenants/:id/history', (s, ctx, [id]) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return s.licenseHistory[id] || []; });
on('POST', '/platform/licensing/tenants/:id/licenses', (s, ctx, [id]) => {
  require(ctx, 'PLATFORM_OPERATIONS');
  const token = String(ctx.body?.licenseToken || '');
  if (token.split('.').length !== 3) throw new HttpError(400, 'The license token is not a valid signed license.', 'LICENSE_INVALID');
  const t = s.licensing.tenants.find((x) => x.tenantId === id);
  if (!t) throw new HttpError(404, 'Tenant not found.');
  const now = Date.now();
  t.status = 'VALID'; t.processingAllowed = true; t.reasonCode = 'LICENSE_VALID'; t.licenseRecordId = demoId('rec');
  if (t.claims) t.claims = { ...t.claims, licenseId: demoId('lic'), issuedAt: iso(now), notBefore: iso(now), expiresAt: iso(now + 365 * DAY) };
  (s.licenseHistory[id] ||= []).forEach((h) => { h.active = false; });
  s.licenseHistory[id].unshift({ ...s.licenseHistory[id][0], recordId: t.licenseRecordId, licenseId: t.claims?.licenseId || 'lic', installedAt: iso(now), installedBy: ctx.actor, active: true, status: 'VALID', claims: t.claims, reasonCode: 'LICENSE_VALID' });
  audit(s, ctx, 'LICENSE_INSTALLED', 'LICENSE', t.licenseRecordId);
  return t;
});
on('GET', '/platform/branding', (s, ctx) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return { deployment: s.brandingDeployment, tenants: s.storage.tenants.map((t) => ({ tenantId: t.tenantId, tenantName: t.name, tenantSlug: t.slug, customBranding: !!s.tenantBranding[t.tenantId] })) }; });
on('GET', '/platform/branding/tenants/:id', (s, ctx, [id]) => { require(ctx, 'TECHNICAL_ADMIN_VIEW'); return { tenantId: id, customBranding: !!s.tenantBranding[id], configuration: s.tenantBranding[id] || null }; });
on('PUT', '/platform/branding/deployment', (s, ctx) => { require(ctx, 'PLATFORM_OPERATIONS'); s.brandingDeployment = { ...(s.brandingDeployment as Json), ...ctx.body }; audit(s, ctx, 'BRANDING_UPDATED', 'BRANDING', 'deployment'); return s.brandingDeployment; });
on('PUT', '/platform/branding/tenants/:id', (s, ctx, [id]) => { require(ctx, 'PLATFORM_OPERATIONS'); s.tenantBranding[id] = { ...(s.brandingDeployment as Json), ...ctx.body, id: demoId('brand'), tenantId: id }; audit(s, ctx, 'BRANDING_UPDATED', 'BRANDING', id); return s.tenantBranding[id]; });
on('DELETE', '/platform/branding/tenants/:id', (s, ctx, [id]) => { require(ctx, 'PLATFORM_OPERATIONS'); delete s.tenantBranding[id]; return undefined; });

on('GET', '/technical-exceptions/:id', (s, ctx, [id]) => {
  require(ctx, 'TECHNICAL_EXCEPTION_VIEW');
  const pkg = findPackage(s, id);
  const incident = s.incidents.find((i) => i.packageId === id);
  const operator = ctx.caps.has('PLATFORM_OPERATIONS') || ctx.caps.has('TECHNICAL_RETRY');
  return {
    packageId: id, documentId: pkg.documents[0]?.documentId ?? null, status: pkg.status, failureCategory: incident?.failureCategory || 'DEPENDENCY_TIMEOUT', failedStage: incident?.stage || 'OCR',
    retryable: pkg.status === 'FAILED', failedAt: pkg.updatedAt, correlationId: incident?.correlationId ?? demoId(), originalRetainedUntil: pkg.technicalHoldUntil,
    explanation: incident?.safeSummary || 'Processing stopped before completion. The original is retained so the package can be retried.',
    incidentId: incident?.incidentId ?? null, incidentStatus: incident?.status ?? null, businessContinuityUsed: incident?.businessContinuityUsed ?? false, businessOutcome: incident?.businessOutcome ?? null,
    processingFingerprint: incident?.processingFingerprint, incidentEvents: incident?.events || [],
    operatorDiagnostics: operator ? { dependency: incident?.component || 'ocr-worker', internalErrorCode: pkg.failureReason || 'E_TIMEOUT', attempts: incident?.attemptCount || 3, environment: 'production · us-west-2' } : null,
  };
});
on('POST', '/technical-exceptions/:id/retry', (s, ctx, [id]) => {
  require(ctx, 'TECHNICAL_RETRY');
  const pkg = findPackage(s, id);
  if (pkg.status !== 'FAILED') throw new HttpError(409, 'Only failed packages can be retried.', 'CONFLICT');
  pkg.reprocessed = true;
  startProcessing(pkg, 6);
  const incident = s.incidents.find((i) => i.packageId === id);
  if (incident) Object.assign(incident, { status: 'RETRY_SCHEDULED', attemptCount: incident.attemptCount + 1, lastObservedAt: iso(Date.now()) });
  audit(s, ctx, 'TECHNICAL_RETRY_REQUESTED', 'PACKAGE', id);
  return undefined;
});
on('GET', '/support/incidents', (s, ctx) => {
  require(ctx, 'TECHNICAL_EXCEPTION_VIEW');
  const q = ctx.query;
  const text = (q.get('query') || q.get('q') || q.get('search') || '').toLowerCase();
  let items = s.incidents.filter((i) => (!q.get('status') || i.status === q.get('status'))
    && (!q.get('stage') || i.stage === q.get('stage'))
    && (!q.get('businessImpact') || i.businessImpact === q.get('businessImpact'))
    && (!q.get('packageId') || i.packageId.includes(q.get('packageId') as string))
    && (!q.get('correlationId') || i.correlationId.includes(q.get('correlationId') as string))
    && (!q.get('reasonCode') || i.reasonCode === q.get('reasonCode'))
    && (!q.get('from') || i.firstObservedAt >= (q.get('from') as string))
    && (!q.get('to') || i.firstObservedAt.slice(0, 10) <= (q.get('to') as string).slice(0, 10))
    && (!text || [i.incidentId, i.packageId, i.primaryIdentifierValue, i.reasonCode, i.correlationId].some((v) => String(v || '').toLowerCase().includes(text))));
  items = items.sort((a, b) => b.lastObservedAt.localeCompare(a.lastObservedAt));
  const page = Number(q.get('page') || 0);
  const size = Number(q.get('size') || 20);
  return { items: items.slice(page * size, page * size + size).map(incidentSummary), total: items.length, page, size };
});
on('GET', '/support/incidents/:id', (s, ctx, [id]) => { require(ctx, 'TECHNICAL_EXCEPTION_VIEW'); const i = s.incidents.find((x) => x.incidentId === id); if (!i) throw new HttpError(404, 'Incident not found.', 'NOT_FOUND'); return i; });
on('POST', '/support/incidents/:id/actions', (s, ctx, [id]) => {
  require(ctx, 'TECHNICAL_RETRY', 'TECHNICAL_HOLD_MANAGE');
  const i = s.incidents.find((x) => x.incidentId === id);
  if (!i) throw new HttpError(404, 'Incident not found.', 'NOT_FOUND');
  const action = String(ctx.body?.action || '');
  const pkg = s.packages.find((p) => p.packageId === i.packageId);
  const now = iso(Date.now());
  if (action === 'RETRY' && pkg) { if (pkg.status === 'FAILED') startProcessing(pkg, 6); i.status = 'RETRY_SCHEDULED'; i.attemptCount += 1; }
  else if (action === 'EXTEND_HOLD' && pkg) { pkg.technicalHoldUntil = iso(Date.now() + Number(ctx.body?.holdDays || 7) * DAY); i.technicalAttentionRetainedUntil = pkg.technicalHoldUntil; }
  else if (action === 'RESOLVE' || action === 'CLOSE') { i.status = 'RESOLVED'; i.resolvedAt = now; i.businessOutcome = ctx.body?.resolutionCode || 'RESOLVED_BY_SUPPORT'; }
  i.lastObservedAt = now;
  i.events = [...i.events, { eventType: `SUPPORT_${action}`, attemptNumber: i.attemptCount, scope: 'PACKAGE', reasonCode: null, retryable: i.retryable, correlationId: demoId(), occurredAt: now, safeMetadata: {} }];
  audit(s, ctx, `SUPPORT_INCIDENT_${action}`, 'INCIDENT', id);
  return { incidentId: id, packageId: i.packageId, action, status: i.status };
});

/* ---------- Scanner bridge simulation ---------- */

const bridgeRoutes: Array<[string, RegExp, Handler]> = [];
const onBridge = (method: string, pattern: string, handler: Handler) => bridgeRoutes.push([method, new RegExp(`^${pattern.replace(/:[a-zA-Z]+/g, '([^/]+)')}$`), handler]);
const bridgeBase = { bridgeId: 'bridge-sea-01', siteId: 'seattle', scannerId: 'fi-7160', captureMode: 'SIMULATOR', bridgeInstanceId: 'bi-demo', bridgeVersion: '1.4.2' };
const requireSession = (s: DemoState, id: string) => { if (!s.scannerSession || s.scannerSession.sessionId !== id) throw new HttpError(404, 'Scan session not found.', 'SESSION_NOT_FOUND'); return s.scannerSession; };

onBridge('GET', '/configuration', () => ({ ...bridgeBase, enabled: true, tenantId: DEMO_TENANT_ID, simulatorInboxPath: 'C:\\LexaVault\\simulator-inbox', stableWindowMs: 1500, maxScanPdfSizeBytes: 100 * 1024 * 1024, minimumUsableSpaceBytes: 2 * 1024 ** 3, prohibitedCapabilities: ['ORIGINAL_DOWNLOAD'], browserRequestToken: 'demo-bridge-token' }));
onBridge('GET', '/scanner-status', (s) => ({ ...bridgeBase, enabled: true, acquisitionAvailable: !getDemoSettings().scannerOffline, storageHealthy: true, usableSpaceBytes: 48 * 1024 ** 3, minimumUsableSpaceBytes: 2 * 1024 ** 3, maxScanPdfSizeBytes: 100 * 1024 * 1024, busy: !!s.scannerSession && !['SUBMITTED', 'CANCELLED'].includes(s.scannerSession.status), activeSessionId: s.scannerSession?.sessionId ?? null, queuedCount: s.scannerDeliveries.filter((d) => d.status === 'QUEUED').length, attentionRequiredCount: s.scannerDeliveries.filter((d) => d.status === 'ATTENTION_REQUIRED').length, checkedAt: iso(Date.now()) }));
onBridge('POST', '/scan-sessions', (s, ctx) => {
  if (getDemoSettings().scannerOffline) throw new HttpError(503, 'The scanner is not reachable. Check that it is powered on and connected.', 'SCANNER_UNAVAILABLE');
  if (s.scannerSession && !['SUBMITTED', 'CANCELLED'].includes(s.scannerSession.status)) throw new HttpError(409, 'A scan session is already active on this scanner.', 'SESSION_ACTIVE');
  const now = iso(Date.now());
  s.scannerSession = { ...bridgeBase, captureMode: 'SIMULATOR', sessionId: demoId('scan'), submissionId: demoId('sub'), tenantId: DEMO_TENANT_ID, status: 'WAITING_FOR_SCAN', businessIdentifiers: ctx.body?.businessIdentifiers || {}, createdAt: now, updatedAt: now, preparedScan: null, lexavaultPackageId: null, deliveryAttemptCount: 0 };
  return s.scannerSession;
});
onBridge('GET', '/scan-sessions/current', (s) => (s.scannerSession && !['SUBMITTED', 'CANCELLED'].includes(s.scannerSession.status) ? s.scannerSession : undefined));
onBridge('GET', '/scan-sessions/:id', (s, _c, [id]) => requireSession(s, id));
onBridge('POST', '/scan-sessions/:id/simulator-pdf', (s, ctx, [id]) => {
  const session = requireSession(s, id);
  const file = ctx.form?.get('file');
  if (!(file instanceof File)) throw new HttpError(400, 'Select a PDF to stage.', 'VALIDATION_FAILED');
  session.preparedScan = { sourceDocumentId: demoId('src'), fileName: file.name, sizeBytes: file.size, sha256: Array.from({ length: 64 }, (_, i) => '0123456789abcdef'[(file.size + i * 7) % 16]).join(''), receivedAt: iso(Date.now()), workReference: `WR-${Date.now().toString(36).toUpperCase()}`, pageCount: Math.max(1, Math.round(file.size / 90_000)) };
  session.status = 'READY';
  session.updatedAt = iso(Date.now());
  return undefined;
});
onBridge('POST', '/scan-sessions/:id/cancel', (s, _c, [id]) => { const session = requireSession(s, id); session.status = 'CANCELLED'; session.updatedAt = iso(Date.now()); return session; });
onBridge('POST', '/scan-sessions/:id/submit', (s, ctx, [id]) => {
  const session = requireSession(s, id);
  if (session.status !== 'READY' || !session.preparedScan) throw new HttpError(409, 'Scan a document before submitting.', 'NOT_READY');
  const now = iso(Date.now());
  const pkg: DemoPackage = {
    packageId: demoId(), packageType: 'SCANNER_INTAKE', status: 'PROCESSING', identifiers: session.businessIdentifiers, createdAt: now, updatedAt: now,
    documents: [{ documentId: demoId(), fileName: session.preparedScan.fileName, mimeType: 'application/pdf', sizeBytes: session.preparedScan.sizeBytes, documentType: 'PDF', status: 'PROCESSING', pageCount: session.preparedScan.pageCount || 1 }],
    successfulExportCycles: 0, lastExportedAt: null, finalApprovalGranted: false, technicalHoldUntil: null, hidden: false, reviewed: false, reprocessed: false, processingCompleteAt: null, failureReason: null, manualFallback: null,
  };
  startProcessing(pkg);
  s.packages.unshift(pkg);
  Object.assign(session, { status: 'SUBMITTED', lexavaultPackageId: pkg.packageId, deliveryAttemptCount: 1, submittedAt: now, updatedAt: now });
  s.scannerDeliveries.unshift({ sessionId: session.sessionId, submissionId: session.submissionId, sourceDocumentId: session.preparedScan.sourceDocumentId, status: 'SUBMITTED', captureMode: 'SIMULATOR', bridgeId: session.bridgeId, siteId: session.siteId, scannerId: session.scannerId, lexavaultPackageId: pkg.packageId, sizeBytes: session.preparedScan.sizeBytes, deliveryAttemptCount: 1, submittedAt: now });
  audit(s, ctx, 'SCANNER_SUBMISSION_RECEIVED', 'PACKAGE', pkg.packageId, session.preparedScan.fileName);
  return session;
});
onBridge('GET', '/delivery-items', (s) => s.scannerDeliveries);
onBridge('POST', '/delivery-items/:id/retry', (s, _c, [id]) => {
  const item = s.scannerDeliveries.find((d) => d.sessionId === id);
  if (!item) throw new HttpError(404, 'Delivery item not found.');
  Object.assign(item, { status: 'SUBMITTED', deliveryAttemptCount: item.deliveryAttemptCount + 1, lastDeliveryErrorCode: null, submittedAt: iso(Date.now()) });
  return { ...(s.scannerSession || {}), ...item };
});

/* ---------- fetch interception ---------- */

const API_PREFIX = '/api/v1';
const BRIDGE_PREFIX = (import.meta.env.VITE_SCAN_BRIDGE_BASE_URL || 'http://127.0.0.1:10090/api/v1').replace(/\/$/, '');
const ALWAYS_AVAILABLE = new Set(['/me', '/branding/current']);

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function dispatch(table: Array<[string, RegExp, Handler]>, path: string, init: RequestInit | undefined, url: URL, bridge: boolean): Promise<Response> {
  const settings = getDemoSettings();
  const method = (init?.method || 'GET').toUpperCase();
  const headers = new Headers(init?.headers || {});
  const roleHeader = headers.get('X-Actor-Roles') || localStorage.getItem(ROLE_STORAGE_KEY) || 'DOCUMENT_PROCESSOR';
  if (!bridge && headers.get('X-Actor-Roles')) localStorage.setItem(ROLE_STORAGE_KEY, roleHeader);
  const roles = roleHeader.split(',').map((r) => r.trim()).filter(Boolean);
  const caps = new Set(capabilitiesFor(roles));

  await wait(settings.latency === 'slow' ? 1200 + Math.random() * 900 : settings.latency === 'off' ? 0 : 120 + Math.random() * 220);
  if (settings.outage && !ALWAYS_AVAILABLE.has(path)) {
    if (bridge) throw new TypeError('Failed to fetch');
    return json({ message: 'The LexaVault service is temporarily unavailable. Try again shortly.', errorCode: 'SERVICE_UNAVAILABLE' }, 503);
  }

  const form = init?.body instanceof FormData ? init.body : null;
  let body: Json = null;
  if (typeof init?.body === 'string' && init.body) { try { body = JSON.parse(init.body); } catch { body = init.body; } }

  const state = loadState();
  advanceProcessing(state);
  const ctx: Ctx = { method, path, query: url.searchParams, body, form, roles, caps, actor: ROLE_USERS[roles[0]] || 'demo.user@northwind.example' };
  for (const [routeMethod, pattern, handler] of table) {
    if (routeMethod !== method) continue;
    const match = path.match(pattern);
    if (!match) continue;
    try {
      const result = await handler(state, ctx, match.slice(1));
      if (method !== 'GET') saveState();
      if (result instanceof Response) return result;
      return result === undefined ? new Response(null, { status: 204 }) : json(result);
    } catch (error) {
      if (error instanceof HttpError) return json({ message: error.message, errorCode: error.errorCode, status: error.status }, error.status);
      console.error('[LexaVault demo] handler failed', path, error);
      return json({ message: 'Unexpected demo server error.', errorCode: 'DEMO_INTERNAL' }, 500);
    }
  }
  return json({ message: `No demo handler for ${method} ${path}`, errorCode: 'NOT_FOUND' }, 404);
}

export function installDemoServer() {
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, window.location.origin);
    if (url.href.startsWith(BRIDGE_PREFIX)) return dispatch(bridgeRoutes, url.href.slice(BRIDGE_PREFIX.length).split('?')[0] || '/', init, url, true);
    if (url.origin === window.location.origin && url.pathname.startsWith(API_PREFIX)) return dispatch(routes, url.pathname.slice(API_PREFIX.length) || '/', init, url, false);
    return originalFetch(input, init);
  };
}

export function storedDemoRole(): string | null {
  return localStorage.getItem(ROLE_STORAGE_KEY);
}

export function storeDemoRole(role: string) {
  localStorage.setItem(ROLE_STORAGE_KEY, role);
}
