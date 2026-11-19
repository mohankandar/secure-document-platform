import assert from 'node:assert/strict';
import test from 'node:test';
import { nextStepText, rowAction } from './caseRules.ts';
import { canContinueManually } from './support/supportRules.ts';

// Capabilities as the backend grants them (ProductAuthorizationPolicy), per role.
const PROCESSOR = ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'FINDING_DECISION', 'REVIEW_SUBMIT'];
const SUPERVISOR = [...PROCESSOR, 'PACKAGE_APPROVE_DELIVERY', 'PACKAGE_REPROCESS'];
const IT_ADMIN = ['TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_RETRY', 'TENANT_INTEGRATION_MANAGE', 'TENANT_AUTH_MANAGE'];
const REPORT_VIEWER = ['REPORT_EXECUTIVE_VIEW'];

test('Review: shown only with MANUAL_REVIEW and open findings', () => {
  assert.deepEqual(rowAction('PENDING_REVIEW', PROCESSOR, 3), { kind: 'review', label: 'Review' });
  assert.deepEqual(rowAction('IN_REVIEW', SUPERVISOR, 1), { kind: 'review', label: 'Review' });
  assert.equal(rowAction('PENDING_REVIEW', PROCESSOR, 0), null, 'nothing to review');
  assert.equal(rowAction('PENDING_REVIEW', REPORT_VIEWER, 3), null);
  assert.equal(rowAction('PENDING_REVIEW', IT_ADMIN, 3), null);
});

test('Approve: shown only with PACKAGE_APPROVE_DELIVERY', () => {
  assert.deepEqual(rowAction('PENDING_FINAL_APPROVAL', SUPERVISOR, 0), { kind: 'approve', label: 'Approve' });
  assert.equal(rowAction('PENDING_FINAL_APPROVAL', ['DASHBOARD_ACCESS', 'MANUAL_REVIEW'], 0), null);
  assert.equal(rowAction('PENDING_FINAL_APPROVAL', IT_ADMIN, 0), null);
});

test('Retry: shown to whoever holds TECHNICAL_RETRY (IT Administrator), before Continue manually', () => {
  assert.deepEqual(rowAction('FAILED', IT_ADMIN, 0), { kind: 'technical-retry', label: 'Retry' });
  assert.deepEqual(rowAction('FAILED', [...SUPERVISOR, 'TECHNICAL_RETRY'], 0), { kind: 'technical-retry', label: 'Retry' });
  assert.equal(rowAction('FAILED', REPORT_VIEWER, 0), null);
});

test('Continue manually: shown with MANUAL_REVIEW and no TECHNICAL_RETRY (Processor and Supervisor)', () => {
  assert.deepEqual(rowAction('FAILED', PROCESSOR, 0), { kind: 'manual-fallback', label: 'Continue manually' });
  assert.deepEqual(rowAction('FAILED', SUPERVISOR, 0), { kind: 'manual-fallback', label: 'Continue manually' });
  assert.equal(rowAction('FAILED', ['DASHBOARD_ACCESS'], 0), null);
});

test('Continue manually on the row follows the one shared rule (canContinueManually)', () => {
  for (const caps of [PROCESSOR, SUPERVISOR, REPORT_VIEWER, ['DASHBOARD_ACCESS'], ['MANUAL_REVIEW']]) {
    assert.equal(rowAction('FAILED', caps, 0)?.kind === 'manual-fallback', canContinueManually(caps), caps.join(','));
  }
});

test('no action for cases nobody needs to move now', () => {
  for (const status of ['CREATED', 'UPLOADED', 'SUBMITTED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'SOMETHING_NEW']) {
    assert.equal(rowAction(status, [...SUPERVISOR, ...IT_ADMIN], 5), null, status);
  }
});

test('the next step in words', () => {
  assert.equal(nextStepText('PENDING_REVIEW', 2), '2 findings to review');
  assert.equal(nextStepText('PENDING_REVIEW', 1), '1 finding to review');
  assert.equal(nextStepText('PENDING_REVIEW', undefined), 'Waiting for review');
  assert.equal(nextStepText('PENDING_FINAL_APPROVAL', 0), 'Redaction done, ready to approve');
  assert.equal(nextStepText('PROCESSING', 0), 'Finding sensitive data…');
  assert.equal(nextStepText('SUBMITTED', 0), 'Queued');
  assert.equal(nextStepText('FAILED', 0), 'Processing stopped');
  assert.equal(nextStepText('REJECTED', 0), 'Not accepted at intake');
  assert.equal(nextStepText('COMPLETED', 0), 'Redacted and sent');
  assert.equal(nextStepText('SOMETHING_NEW', 0), 'Status not recognised');
});
