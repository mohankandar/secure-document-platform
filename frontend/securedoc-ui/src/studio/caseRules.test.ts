import assert from 'node:assert/strict';
import test from 'node:test';
import { deliveryLabel, nextStepText, rowAction, rowDestination, rowReason } from './caseRules.ts';
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

/* ---------- Task 0023 B4: the case panel's delivery line never shows a file name ---------- */

test('0023 B4: a delivery is named by its destination, else the document type, else a short document id; never the file name', () => {
  const base = { sourceDocumentId: '3f2a9c1e-0000-4000-8000-000000000001', fileName: 'Maria_Alvarez_title.pdf' };
  assert.equal(deliveryLabel({ ...base, destinationLabel: 'Title pages' }, 'DEED'), 'Title pages');
  assert.equal(deliveryLabel({ ...base, destinationLabel: '' }, 'TITLE_COMMITMENT'), 'Title commitment');
  assert.equal(deliveryLabel({ ...base }, undefined), 'Document 3f2a9c1e');
  assert.equal(deliveryLabel({ ...base, destinationLabel: '   ' }, '  '), 'Document 3f2a9c1e');
  assert.equal(deliveryLabel({ sourceDocumentId: '', fileName: 'Maria_Alvarez_title.pdf' }, undefined), 'Destination');
  for (const label of [deliveryLabel(base, undefined), deliveryLabel(base, 'DEED'), deliveryLabel({ ...base, sourceDocumentId: '' }, undefined)]) {
    assert.equal(label.includes('Maria'), false);
    assert.equal(label.includes('.pdf'), false);
  }
});

// Task 0038: the plain stop reason and the destination on each row.
test('a stopped, rejected or waiting row says why in the code title; other rows say nothing more', () => {
  assert.equal(rowReason({ status: 'FAILED', stopReasonCode: 'LV-PRC-004' }), 'Text reading timed out');
  assert.equal(rowReason({ status: 'IN_REVIEW', exportState: 'NEEDS_IT', stopReasonCode: 'LV-DLV-004' }), 'Destination refused the file');
  assert.equal(rowReason({ status: 'APPROVED', exportState: 'WAITING_FOR_YOU', stopReasonCode: 'LV-DLV-004' }), 'Destination refused the file');
  assert.equal(rowReason({ status: 'REJECTED', stopReasonCode: 'LV-PRC-004' }), 'Text reading timed out');
  assert.equal(rowReason({ status: 'REJECTED' }), null, 'no rejection reason is stored yet');
  assert.equal(rowReason({ status: 'IN_REVIEW', stopReasonCode: 'LV-PRC-004' }), null, 'a case in review neither stopped nor waits');
  assert.equal(rowReason({ status: 'FAILED', stopReasonCode: 'Jane Doe 123-45-6789' }), null, 'never text that is no known code');
  assert.equal(rowReason({ status: 'FAILED', stopReasonCode: null }), null);
});

test('the destination shows once one is chosen', () => {
  assert.equal(rowDestination({ destinationName: 'Records archive' }), 'To Records archive');
  assert.equal(rowDestination({ destinationName: '  ' }), null);
  assert.equal(rowDestination({}), null);
});
