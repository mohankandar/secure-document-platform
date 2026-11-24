import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { REJECTION_CODES, isRejectionCode, rejectStep, rejectionName } from './rejectRules.ts';
import { rowReason } from './caseRules.ts';

const REVIEWER = ['MANUAL_REVIEW'];
const APPROVER = ['PACKAGE_APPROVE_DELIVERY'];

test('task 0071: the reasons are exactly the server list, each with a plain name', () => {
  const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'backend', 'securedoc-domain',
    'src', 'main', 'java', 'com', 'securedoc', 'platform', 'domain', 'review', 'RejectionReason.java'), 'utf8');
  assert.deepEqual([...source.matchAll(/LvErrorCode\.REV_(\d+)\)/g)].map((m) => `LV-REV-${m[1]}`), [...REJECTION_CODES]);
  assert.deepEqual(REJECTION_CODES.map(rejectionName), ['Wrong document', 'Unreadable', 'Out of scope', 'Duplicate', 'Other (see ticket)']);
  assert.equal(isRejectionCode('LV-REV-010'), true);
  assert.equal(isRejectionCode('LV-REV-002'), false);
  assert.equal(isRejectionCode(null), false);
});

test('task 0071: only whoever may review or approve, and only while the case waits there', () => {
  assert.equal(rejectStep('PENDING_REVIEW', REVIEWER), 'review');
  assert.equal(rejectStep('IN_REVIEW', REVIEWER), 'review');
  assert.equal(rejectStep('PENDING_FINAL_APPROVAL', APPROVER), 'approval');
  assert.equal(rejectStep('PENDING_REVIEW', APPROVER), null);
  assert.equal(rejectStep('PENDING_FINAL_APPROVAL', REVIEWER), null);
  for (const status of ['PROCESSING', 'FAILED', 'COMPLETED', 'REJECTED']) assert.equal(rejectStep(status, [...REVIEWER, ...APPROVER]), null);
});

test('task 0071: the Cases list shows the reason of a rejected case', () => {
  assert.equal(rowReason({ status: 'REJECTED', stopReasonCode: 'LV-REV-013' }), 'Rejected: duplicate');
});
