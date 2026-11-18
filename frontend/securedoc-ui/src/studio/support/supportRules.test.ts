import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PROBLEM_REASONS, problemReason, processingStageWords } from '../words.ts';
import {
  MORE_ACTIONS, SUPPORT_CAPS, blockingCount, canContinueManually, canFinish, chipCounts, fromIncident, fromTechnical, impactChip,
  inChip, lastSeen, pagesLine, problemActions, rowWhat, rowWhere, searchPlaceholder, statusWord, supportSearch, timelineText,
  type IncidentLike, type TechnicalLike,
} from './supportRules.ts';

const when = (iso: string) => `<${iso}>`;
const IT_ADMIN = ['TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW', 'TECHNICAL_RETRY', 'TECHNICAL_HOLD_MANAGE', 'AUDIT_VIEW'];
const PLATFORM_OPS = ['TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW', 'AUDIT_VIEW', 'PLATFORM_DIAGNOSTICS', 'PLATFORM_OPERATIONS'];
const PROCESSOR = ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW'];
const SUPERVISOR = [...PROCESSOR, 'PACKAGE_APPROVE_DELIVERY', 'PACKAGE_REPROCESS'];

const incident = (over: Partial<IncidentLike> = {}): IncidentLike => ({
  incidentId: 'i-1', tenantId: 't-1', packageId: 'p-1', documentId: 'd-1', packageStatus: 'FAILED',
  primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: 'LN204860', status: 'OPEN', businessOutcome: null,
  businessImpact: 'BLOCKED', businessContinuityUsed: false, stage: 'OCR', component: 'ocr-worker', failureCategory: 'DEPENDENCY_TIMEOUT',
  reasonCode: 'OCR_TIMEOUT', retryable: true, attemptCount: 3, correlationId: 'corr-93', safeSummary: 'OCR did not complete.',
  artifactAvailability: 'RETAINED_UNTIL_POLICY_EXPIRY', technicalAttentionRetainedUntil: '2026-11-20T09:20:00Z', artifactsPurgedAt: null,
  processingFingerprint: { bundle: '2026.11.1' }, safeMetadata: { page: '14' },
  firstObservedAt: '2026-11-17T17:30:00Z', lastObservedAt: '2026-11-17T17:50:00Z', resolvedAt: null,
  events: [
    { eventType: 'PROCESSING_RECOVERY_EXHAUSTED', occurredAt: '2026-11-17T17:50:01Z', correlationId: 'corr-93' },
    { eventType: 'FAILURE_RECORDED', attemptNumber: 1, reasonCode: 'OCR_TIMEOUT', occurredAt: '2026-11-17T17:30:00Z', correlationId: 'corr-91' },
    { eventType: 'FAILURE_RECORDED', attemptNumber: 3, reasonCode: 'OCR_TIMEOUT', occurredAt: '2026-11-17T17:50:00Z', correlationId: 'corr-93' },
  ],
  ...over,
});

test('reason codes read as plain sentences; unknown codes read "Processing problem"', () => {
  assert.equal(problemReason('OCR_TIMEOUT').title, 'Text recognition timed out');
  assert.equal(problemReason('SDV_UNAVAILABLE').title, 'Secure Document Vault didn’t answer');
  assert.equal(problemReason('MODEL_TIMEOUT').title, 'AI model didn’t answer in time');
  assert.equal(problemReason('ocr_timeout').known, true);
  const unknown = problemReason('NEW_THING_FAILED');
  assert.equal(unknown.title, 'Processing problem');
  assert.equal(unknown.known, false);
  assert.equal(problemReason(null).title, 'Processing problem');
  for (const [code, r] of Object.entries(PROBLEM_REASONS)) {
    assert.doesNotMatch(`${r.title} ${r.sentence}`, /[A-Z]{2,}_[A-Z]/, code);
    assert.doesNotMatch(r.title, /\bOCR\b/, `${code} says "text recognition", not OCR`);
  }
  assert.equal(processingStageWords('OCR'), 'text recognition');
  assert.equal(processingStageWords('SOMETHING_NEW'), 'processing');
});

test('impact chips and status words', () => {
  assert.deepEqual(impactChip('BLOCKED'), { stage: 'stopped', label: 'Case stopped' });
  assert.deepEqual(impactChip('CONTINUED'), { stage: 'approval', label: 'Continued manually' });
  assert.deepEqual(impactChip('COMPLETED'), { stage: 'delivered', label: 'Case finished' });
  assert.equal(impactChip('ODD_VALUE').stage, 'unknown');
  assert.equal(impactChip('ODD_VALUE').label, 'Odd value');
  for (const s of ['OPEN', 'RETRY_SCHEDULED', 'INVESTIGATING', 'ESCALATED']) assert.equal(statusWord(s), 'Open');
  assert.equal(statusWord('BUSINESS_CONTINUED'), 'Watching');
  assert.equal(statusWord('RESOLVED'), 'Resolved');
  assert.equal(statusWord('AUTO_CLOSED'), 'Resolved');
});

test('Support list: chips, counts, row words and search', () => {
  const rows = [
    { status: 'OPEN', businessImpact: 'BLOCKED' },
    { status: 'BUSINESS_CONTINUED', businessImpact: 'CONTINUED' },
    { status: 'RESOLVED', businessImpact: 'COMPLETED' },
  ];
  assert.deepEqual(chipCounts(rows), { open: 2, resolved: 1, all: 3 });
  assert.equal(blockingCount(rows), 1);
  assert.equal(rows.filter((r) => inChip(r, 'open')).length, 2);
  assert.equal(rows.filter((r) => inChip(r, 'resolved')).length, 1);

  const row = { incidentId: 'i', reasonCode: 'OCR_TIMEOUT', stage: 'OCR', status: 'OPEN', businessImpact: 'BLOCKED', attemptCount: 3 };
  assert.deepEqual(rowWhat(row), { text: 'Text recognition timed out', note: 'tried 3 times' });
  assert.deepEqual(rowWhat({ ...row, reasonCode: 'SDV_UNAVAILABLE', businessImpact: 'CONTINUED' }), { text: 'Secure Document Vault didn’t answer', note: 'case continued manually' });
  assert.deepEqual(rowWhat({ ...row, attemptCount: 1 }), { text: 'Text recognition timed out' });
  assert.equal(rowWhere(row), 'At text recognition');

  assert.deepEqual(supportSearch('  LN204860 '), { businessIdentifier: 'LN204860' });
  assert.deepEqual(supportSearch('3F2A91C4-1111-4222-8333-444455556666'), { incidentId: '3f2a91c4-1111-4222-8333-444455556666' });
  assert.deepEqual(supportSearch('   '), {});
  assert.equal(searchPlaceholder([{ primaryIdentifierLabel: null }, { primaryIdentifierLabel: 'Loan number' }]), 'Loan number or incident number');
  assert.equal(searchPlaceholder([]), 'Case or incident number');

  const now = Date.parse('2026-11-18T12:00:00Z');
  assert.equal(lastSeen('2026-11-18T09:00:00Z', now), '3h ago');
  assert.equal(lastSeen('2026-11-18T11:55:00Z', now), '5 min ago');
  assert.equal(lastSeen('2026-11-16T12:00:00Z', now), '2 days ago');
  assert.equal(lastSeen(null, now), '—');
});

test('problem page from an incident: three plain blocks, a short timeline, every code under Technical details', () => {
  const v = fromIncident(incident(), when);
  assert.equal(v.title, 'Text recognition timed out');
  assert.deepEqual(v.impact, { stage: 'stopped', label: 'Case stopped' });
  assert.equal(v.caseValue, 'LN204860');
  assert.equal(v.happened, 'Text recognition didn’t finish within the allowed time. It stopped at text recognition. LexaVault tried 3 times, the last at <2026-11-17T17:50:00Z>.');
  assert.equal(v.meaning, 'The case is stopped. Nothing was sent. The original is kept until <2026-11-20T09:20:00Z>; after that the case can’t be finished.');
  assert.deepEqual(v.timeline.map((t) => t.text), [
    'Try 1 failed: text recognition timed out',
    'Try 3 failed: text recognition timed out',
    'No tries left; case stopped and support told',
  ]);
  for (const line of [v.title, v.happened, v.meaning, ...v.timeline.map((t) => t.text)]) assert.doesNotMatch(line, /[A-Z]{2,}_[A-Z]/);
  const tech = Object.fromEntries(v.technical);
  assert.equal(tech.Incident, 'i-1');
  assert.equal(tech.Reason, 'OCR_TIMEOUT');
  assert.equal(tech.Correlation, 'corr-93');
  assert.equal(tech.bundle, '2026.11.1');
  assert.equal(tech.page, '14');
  assert.match(tech['Step 1'], /FAILURE_RECORDED · OCR_TIMEOUT · attempt 1 · corr-91/);

  assert.equal(fromIncident(incident({ artifactAvailability: 'PURGED' }), when).meaning, 'The case is stopped. Nothing was sent. The original has been deleted, so the case can’t be finished.');
  assert.match(fromIncident(incident({ businessImpact: 'CONTINUED', status: 'BUSINESS_CONTINUED' }), when).meaning, /^A person is finishing the case with Continue manually/);
  assert.equal(fromIncident(incident({ businessImpact: 'COMPLETED', status: 'RESOLVED' }), when).meaning, 'The case was finished and sent.');
  assert.equal(fromIncident(incident({ reasonCode: 'BRAND_NEW' }), when).title, 'Processing problem');

  const many = Array.from({ length: 9 }, (_, i) => ({ eventType: 'RETRY_SCHEDULED', occurredAt: `2026-11-17T10:0${i}:00Z` }));
  const short = fromIncident(incident({ events: many }), when);
  assert.equal(short.timeline.length, 6);
  assert.equal(short.earlierSteps, 3);
  assert.equal(timelineText({ eventType: 'SOMETHING_NEW', occurredAt: '' }), 'Something new');
  assert.equal(timelineText({ eventType: 'FAILURE_RECORDED', reasonCode: 'AI_TIMEOUT', attemptNumber: 2, occurredAt: '' }), 'Try 2 failed: AI model didn’t answer in time');
});

test('problem page from a stopped case without an incident', () => {
  const t: TechnicalLike = {
    packageId: 'p-2', status: 'ATTENTION_REQUIRED', failureCategory: 'DEPENDENCY_TIMEOUT', failedStage: 'AI_ANALYSIS', retryable: true,
    failedAt: '2026-11-17T08:00:00Z', explanation: 'The configured AI provider did not respond.', originalRetainedUntil: '2026-11-19T08:00:00Z',
    incidentEvents: [{ eventType: 'FAILURE_RECORDED', reasonCode: 'AI_TIMEOUT', attemptNumber: 1, occurredAt: '2026-11-17T08:00:00Z' }],
  };
  const v = fromTechnical(t, when);
  assert.equal(v.title, 'AI model didn’t answer in time');
  assert.equal(v.incidentId, undefined);
  assert.match(v.meaning, /kept until <2026-11-19T08:00:00Z>/);
  assert.equal(fromTechnical({ ...t, incidentEvents: [] }, when).title, 'Processing problem');
});

test('roles: Retry for IT Administrator only; keep for IT Administrator; Platform Operations sees neither', () => {
  const open = fromIncident(incident(), when);
  assert.deepEqual(problemActions(open, IT_ADMIN, false), { retry: true, keep: true, manualHint: true });
  assert.deepEqual(problemActions(open, PLATFORM_OPS, false), { retry: false, keep: false, manualHint: true });
  // Another organization (Platform Operations looking across): the server refuses actions, so none are shown.
  assert.deepEqual(problemActions(open, [...IT_ADMIN, 'PLATFORM_OPERATIONS'], true), { retry: false, keep: false, manualHint: false });
  // Closed, or the case no longer stopped: nothing to do.
  assert.deepEqual(problemActions(fromIncident(incident({ status: 'RESOLVED' }), when), IT_ADMIN, false), { retry: false, keep: false, manualHint: false });
  assert.equal(problemActions(fromIncident(incident({ packageStatus: 'COMPLETED', businessImpact: 'COMPLETED' }), when), IT_ADMIN, false).retry, false);
  // Without an incident: Retry only, and only when the server says it can be retried.
  const bare = fromTechnical({ packageId: 'p', status: 'X', failureCategory: 'X', failedStage: 'OCR', retryable: true, failedAt: '', explanation: '' }, when);
  assert.deepEqual(problemActions(bare, IT_ADMIN, false), { retry: true, keep: false, manualHint: true });
  assert.equal(problemActions({ ...bare, retryable: false }, IT_ADMIN, false).retry, false);
  assert.equal(problemActions(bare, PLATFORM_OPS, false).retry, false);
  for (const a of MORE_ACTIONS) assert.doesNotMatch(a.label, /[A-Z]{2,}_/);
  assert.equal(SUPPORT_CAPS.retry, 'TECHNICAL_RETRY');
});

test('Continue manually: Document Processor and Supervisor only; finish waits for the confirmation', () => {
  assert.equal(canContinueManually(PROCESSOR), true);
  assert.equal(canContinueManually(SUPERVISOR), true);
  assert.equal(canContinueManually(IT_ADMIN), false);
  assert.equal(canContinueManually(PLATFORM_OPS), false);
  assert.equal(pagesLine(3, 1), '3 pages · 1 marked');
  assert.equal(pagesLine(1, 0), '1 page · 0 marked');
  assert.equal(canFinish(false, false), false);
  assert.equal(canFinish(true, true), false);
  assert.equal(canFinish(true, false), true);
});
