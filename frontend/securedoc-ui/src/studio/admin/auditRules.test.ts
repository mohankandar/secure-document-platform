import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  AUDIT_TYPES, SYSTEM_ACTOR_ID, actionOptions, caseId, caseSearch, changeLine, periodStart, personName, personOptions, recordedFields, resultWord, whatHappened,
} from './auditRules.ts';

const PKG = '3f2a91c4-1111-4222-8333-444455556666';
const rec = (action: string, detail?: string, extra: Partial<{ resourceType: string; resourceId: string; actorId: string; outcome: string }> = {}) => ({
  actorId: extra.actorId ?? 'a1b2c3d4-0000-4000-8000-000000000001', action, detail,
  resourceType: extra.resourceType ?? 'Finding', resourceId: extra.resourceId ?? 'f-1', outcome: extra.outcome ?? 'SUCCESS',
});

test('type chips use the design words and the server categories', () => {
  assert.deepEqual(AUDIT_TYPES.map((t) => t.label), ['Everything', 'Sign-in', 'Settings', 'Cases', 'Exports', 'Refused']);
  assert.deepEqual(AUDIT_TYPES.map((t) => t.key), ['MEANINGFUL', 'SECURITY', 'ADMINISTRATION', 'DOCUMENTS', 'EXPORTS', 'FAILURES_DENIED']);
});

test('rows read as sentences, never raw codes', () => {
  assert.equal(whatHappened(rec('PACKAGE_FINAL_APPROVED')), 'Approved delivery');
  assert.equal(whatHappened(rec('FINDING_CONFIRMED', 'Finding confirmed: type=SSN')), 'Redacted a Social Security Number');
  assert.equal(whatHappened(rec('FINDING_CONFIRMED', 'Focused review confirmed finding: type=US_SSN')), 'Redacted a Social Security Number');
  assert.equal(whatHappened(rec('FINDING_DISMISSED', 'Finding dismissed: type=CREDIT_SCORE')), 'Kept a credit score visible');
  assert.equal(whatHappened(rec('FINDING_CONFIRMED', 'type=DATE_OF_BIRTH')), 'Redacted a date of birth');
  assert.equal(whatHappened(rec('FINDING_CONFIRMED', 'type=ACCOUNT_NUMBER')), 'Redacted an account number');
  assert.equal(whatHappened(rec('FINDING_CONFIRMED')), 'Redacted a finding');
  assert.equal(whatHappened(rec('SENSITIVE_DATA_POLICY_CHANGED')), 'Changed the sensitive-data settings');
  assert.equal(whatHappened(rec('ENTERPRISE_IDENTITY_CONNECTION_TESTED')), 'Tested the sign-in setup');
  // An action this screen doesn't know yet is still words, not a code.
  const unknown = whatHappened(rec('SOMETHING_NEW_HAPPENED'));
  assert.doesNotMatch(unknown, /_/);
  assert.match(unknown, /^Something new happened$/i);
});

test('the change line uses only what is recorded (the new value)', () => {
  const detail = 'Sensitive-data policy changed: enabledCount=4, threshold=0.85, reviewMode=FOCUSED, maskSsnLastFour=true';
  assert.equal(changeLine(rec('SENSITIVE_DATA_POLICY_CHANGED', detail)), 'auto-redact level now 85%');
  assert.equal(changeLine(rec('SENSITIVE_DATA_POLICY_CHANGED', 'threshold=abc')), undefined);
  assert.equal(changeLine(rec('PACKAGE_APPROVED', 'threshold=0.5')), undefined);
});

test('task 0016 detail: the new keys and the previous policy do not change the change line or the sentence', () => {
  const detail = 'Sensitive-data policy changed: enabledCount=3, threshold=0.70, reviewMode=UNCERTAIN_ONLY, '
    + 'maskSsnLastFour=false, enabledTypes=CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=LIGHT, '
    + 'previous=[enabledCount=2, threshold=0.80, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, '
    + 'enabledTypes=CREDIT_SCORE,SSN, preset=BALANCED]';
  assert.equal(changeLine(rec('SENSITIVE_DATA_POLICY_CHANGED', detail)), 'auto-redact level now 70%');
  assert.equal(changeLine(rec('SENSITIVE_DATA_POLICY_CHANGED',
    'Sensitive-data policy changed: enabledCount=1, threshold=0.85, reviewMode=REVIEW_ALL, maskSsnLastFour=true, '
    + 'enabledTypes=SSN, preset=STRICT, previous=none')), 'auto-redact level now 85%');
  assert.equal(whatHappened(rec('SENSITIVE_DATA_POLICY_CHANGED', detail)), 'Changed the sensitive-data settings');
  // The new action has no sentence yet: the default sentence, never a raw code.
  assert.equal(whatHappened(rec('COMPLIANCE_REPORT_CREATED')), 'Compliance report created');
});

test('who: the system actor by name, anyone else by a short id', () => {
  assert.equal(personName(SYSTEM_ACTOR_ID), 'LexaVault (automatic)');
  assert.equal(personName('a1b2c3d4-0000-4000-8000-000000000001'), 'Person 00000001');
  assert.equal(personName(''), 'Unknown');
});

test('case: the package itself, or package= in the detail; nothing otherwise', () => {
  assert.equal(caseId(rec('PACKAGE_APPROVED', undefined, { resourceType: 'DocumentPackage', resourceId: PKG })), PKG);
  assert.equal(caseId(rec('REVIEW_APPROVED', `Review approved for package=${PKG}`, { resourceType: 'ReviewTask', resourceId: 'r-1' })), PKG);
  assert.equal(caseId(rec('FINDING_CONFIRMED', 'type=SSN')), undefined);
});

test('result words', () => {
  assert.deepEqual(resultWord('SUCCESS'), { word: 'Success', tone: 'ok' });
  assert.deepEqual(resultWord('DENIED'), { word: 'Refused', tone: 'danger' });
  assert.deepEqual(resultWord('FAILURE'), { word: 'Failed', tone: 'danger' });
  assert.equal(resultWord('ODD').tone, 'neutral');
});

test('case search takes a case id and refuses a loan number with a reason', () => {
  assert.deepEqual(caseSearch(''), {});
  assert.deepEqual(caseSearch(` ${PKG.toUpperCase()} `), { caseId: PKG });
  const r = caseSearch('LN204877');
  assert.equal(r.caseId, undefined);
  assert.match(r.error ?? '', /loan numbers/);
});

test('periods', () => {
  const now = Date.parse('2026-11-18T12:00:00Z');
  assert.equal(periodStart('24h', now), '2026-11-17T12:00:00.000Z');
  assert.equal(periodStart('7d', now), '2026-11-11T12:00:00.000Z');
  assert.equal(periodStart('all', now), undefined);
});

test('the opened row lists every field exactly as recorded', () => {
  const r = { ...rec('FINDING_CONFIRMED', 'Finding confirmed: type=SSN'), id: 'id-1', correlationId: 'c-1', occurredAt: '2026-11-17T21:17:00Z' };
  const fields = Object.fromEntries(recordedFields(r));
  assert.equal(fields.Action, 'FINDING_CONFIRMED');
  assert.equal(fields.Detail, 'Finding confirmed: type=SSN');
  assert.equal(fields['Person ID'], r.actorId);
  assert.equal(fields.Reference, 'c-1');
  assert.equal(fields['Record ID'], 'id-1');
});

test('the action filter lists every action by its words, none twice', () => {
  const opts = actionOptions();
  assert.ok(opts.length > 100);
  assert.equal(new Set(opts.map((o) => o[0])).size, opts.length);
  for (const [, label] of opts) assert.doesNotMatch(label, /[A-Z]{2,}_/);
  assert.ok(opts.some(([a, l]) => a === 'FINDING_DISMISSED' && l === 'Kept a finding visible'));
});

test('0019 A2: who shows the recorded name; the system and older records keep their labels', () => {
  const id = 'a1b2c3d4-0000-4000-8000-000000000099';
  assert.equal(personName(id, 'Dana Reviewer'), 'Dana Reviewer');
  assert.equal(personName(id, '  Dana Reviewer  '), 'Dana Reviewer');
  assert.equal(personName(id, null), 'Person 00000099');
  assert.equal(personName(id, ''), 'Person 00000099');
  assert.equal(personName(id, '   '), 'Person 00000099');
  assert.equal(personName(SYSTEM_ACTOR_ID, 'Someone'), 'LexaVault (automatic)');
});

test('0019 A2: the record detail shows the recorded name only when there is one', () => {
  const base = { ...rec('FINDING_CONFIRMED'), id: 'id-1', correlationId: 'c-1', occurredAt: '2026-11-20T10:00:00Z' };
  const named = recordedFields({ ...base, actorDisplayName: 'Dana Reviewer' });
  assert.deepEqual(named.slice(3, 5), [['Person', 'Dana Reviewer'], ['Person ID', base.actorId]]);
  assert.equal(recordedFields({ ...base, actorDisplayName: null }).some(([k]) => k === 'Person'), false);
  assert.equal(recordedFields(base).some(([k]) => k === 'Person'), false);
});

test('0019 A2: the Person filter keeps ids as values and shows names where it can', () => {
  const dana = 'a1b2c3d4-0000-4000-8000-0000000000d1';
  const old = 'a1b2c3d4-0000-4000-8000-0000000000e2';
  const twinA = 'a1b2c3d4-0000-4000-8000-0000000000a1';
  const twinB = 'a1b2c3d4-0000-4000-8000-0000000000b2';
  const asked = 'a1b2c3d4-0000-4000-8000-0000000000f3';
  const options = personOptions([
    { actorId: dana, actorDisplayName: null, occurredAt: '2026-11-01T00:00:00Z' },
    { actorId: dana, actorDisplayName: 'Dana Old Name', occurredAt: '2026-11-02T00:00:00Z' },
    { actorId: dana, actorDisplayName: 'Dana Reviewer', occurredAt: '2026-11-03T00:00:00Z' },
    { actorId: old, actorDisplayName: null, occurredAt: '2026-10-01T00:00:00Z' },
    { actorId: twinA, actorDisplayName: 'Sam Lee', occurredAt: '2026-11-01T00:00:00Z' },
    { actorId: twinB, actorDisplayName: 'Sam Lee', occurredAt: '2026-11-01T00:00:00Z' },
    { actorId: SYSTEM_ACTOR_ID, actorDisplayName: null, occurredAt: '2026-11-01T00:00:00Z' },
  ], [asked, '', SYSTEM_ACTOR_ID]);
  assert.deepEqual(options, [
    { id: dana, label: 'Dana Reviewer' },
    { id: old, label: 'Person 000000e2' },
    { id: asked, label: 'Person 000000f3' },
    { id: twinA, label: 'Sam Lee · 000000a1' },
    { id: twinB, label: 'Sam Lee · 000000b2' },
  ]);
});

test('0023 B1: a case a connection run could not take in has its own sentence and filter entry', () => {
  assert.equal(whatHappened(rec('POLLING_PACKAGE_FAILED')), 'Could not take in a case from a connection');
  assert.ok(actionOptions().some(([code, words]) => code === 'POLLING_PACKAGE_FAILED' && words === 'Could not take in a case from a connection'));
  assert.notEqual(whatHappened(rec('POLLING_PACKAGE_FAILED')), whatHappened(rec('POLLING_RUN_FAILED')));
});

test('0029: every export action of tasks 0027 and 0028 has its own sentence and filter entry, none twice', async () => {
  const { readFileSync } = await import('node:fs');
  const { dirname, resolve } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const here = dirname(fileURLToPath(import.meta.url));
  const java = readFileSync(resolve(here, '..', '..', '..', '..', '..', 'backend', 'securedoc-domain', 'src', 'main', 'java', 'com', 'securedoc',
    'platform', 'domain', 'audit', 'AuditAction.java'), 'utf8');
  const actions = [...java.matchAll(/^\s*((?:EXPORT|SPLIT_COPY)_[A-Z_]+)\s*[,}\n]/gm)].map((m) => m[1]);
  assert.equal(actions.length, 16, 'the 8 actions of 0027 and the 8 of 0028');
  const words = actions.map((a) => whatHappened(rec(a)));
  for (const [i, a] of actions.entries()) {
    assert.doesNotMatch(words[i], /_|^Export (job|destination)/, `${a} has its own sentence, not its code`);
    assert.ok(actionOptions().some(([code]) => code === a), `${a} is in the Action filter`);
  }
  assert.equal(new Set(words).size, words.length, 'no two actions read the same');
  assert.equal(whatHappened(rec('EXPORT_JOB_RESEND_ALL_REQUESTED')), 'Sent again every case a destination refused');
  assert.equal(whatHappened(rec('EXPORT_RETENTION_PERIOD_CHANGED')), 'Changed how long exported copies are kept');
});
