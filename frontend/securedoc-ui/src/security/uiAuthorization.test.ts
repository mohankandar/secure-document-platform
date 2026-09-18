import assert from 'node:assert/strict';
import test from 'node:test';
import { ROLE_OPTIONS, canAccessSurface } from './uiAuthorization.ts';

const capabilitiesByRole: Record<string, string[]> = {
  DOCUMENT_PROCESSOR: ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'FINDING_DECISION', 'REDACTION_ADJUST', 'REVIEW_SUBMIT', 'PACKAGE_APPROVE_DELIVERY'],
  DOCUMENT_SUPERVISOR: ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'FINDING_DECISION', 'REDACTION_ADJUST', 'REVIEW_SUBMIT', 'PACKAGE_APPROVE_DELIVERY', 'PACKAGE_REPROCESS', 'PACKAGE_REMOVE_FROM_DASHBOARD'],
  BUSINESS_ADMIN: ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'FINDING_DECISION', 'REDACTION_ADJUST', 'REVIEW_SUBMIT', 'PACKAGE_APPROVE_DELIVERY', 'PACKAGE_REPROCESS', 'PACKAGE_REMOVE_FROM_DASHBOARD', 'REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW', 'REPORT_EXPORT', 'TENANT_POLICY_MANAGE', 'TENANT_IDENTIFIER_MANAGE'],
  REPORT_VIEWER: ['REPORT_EXECUTIVE_VIEW'],
  TENANT_IT_ADMIN: ['DASHBOARD_ACCESS', 'REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW', 'REPORT_EXPORT', 'TENANT_INTEGRATION_MANAGE', 'TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW', 'TECHNICAL_RETRY', 'TECHNICAL_HOLD_MANAGE', 'AUDIT_VIEW'],
  PLATFORM_OPERATIONS_ADMIN: ['DASHBOARD_ACCESS', 'REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW', 'REPORT_EXPORT', 'TENANT_INTEGRATION_MANAGE', 'TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW', 'TECHNICAL_RETRY', 'TECHNICAL_HOLD_MANAGE', 'AUDIT_VIEW', 'PLATFORM_DIAGNOSTICS', 'PLATFORM_OPERATIONS'],
  ADMIN: ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'REPORT_EXECUTIVE_VIEW', 'REPORT_EXPORT', 'TENANT_POLICY_MANAGE', 'TENANT_INTEGRATION_MANAGE', 'TECHNICAL_ADMIN_VIEW', 'AUDIT_VIEW'],
};

test('demo selector exposes canonical product roles only', () => {
  assert.deepEqual(ROLE_OPTIONS.map((option) => option.value), [
    'DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'BUSINESS_ADMIN', 'REPORT_VIEWER',
    'TENANT_IT_ADMIN', 'PLATFORM_OPERATIONS_ADMIN', 'ADMIN',
  ]);
});

test('document processor UI exposes business workflow but not reports/admin', () => {
  const caps = capabilitiesByRole.DOCUMENT_PROCESSOR;
  assert.equal(canAccessSurface(caps, 'dashboard'), true);
  assert.equal(canAccessSurface(caps, 'upload'), true);
  assert.equal(canAccessSurface(caps, 'review'), true);
  assert.equal(canAccessSurface(caps, 'reports'), false);
  assert.equal(canAccessSurface(caps, 'businessAdmin'), false);
  assert.equal(canAccessSurface(caps, 'integrations'), false);
});

test('document supervisor retains processor UI surface', () => {
  const caps = capabilitiesByRole.DOCUMENT_SUPERVISOR;
  assert.equal(canAccessSurface(caps, 'dashboard'), true);
  assert.equal(canAccessSurface(caps, 'upload'), true);
  assert.equal(canAccessSurface(caps, 'review'), true);
});

test('business administrator gets workflow, reports, export and business admin', () => {
  const caps = capabilitiesByRole.BUSINESS_ADMIN;
  for (const surface of ['dashboard', 'upload', 'review', 'reports', 'reportExport', 'businessAdmin'] as const) {
    assert.equal(canAccessSurface(caps, surface), true, surface);
  }
  assert.equal(canAccessSurface(caps, 'technicalAdmin'), false);
  assert.equal(canAccessSurface(caps, 'integrations'), false);
  assert.equal(canAccessSurface(caps, 'audit'), false);
});

test('report viewer is reports-only and cannot export', () => {
  const caps = capabilitiesByRole.REPORT_VIEWER;
  assert.equal(canAccessSurface(caps, 'reports'), true);
  assert.equal(canAccessSurface(caps, 'dashboard'), false);
  assert.equal(canAccessSurface(caps, 'upload'), false);
  assert.equal(canAccessSurface(caps, 'review'), false);
  assert.equal(canAccessSurface(caps, 'reportExport'), false);
});

test('tenant IT administrator gets dashboard, reports, export, technical admin and audit only', () => {
  const caps = capabilitiesByRole.TENANT_IT_ADMIN;
  assert.equal(canAccessSurface(caps, 'dashboard'), true);
  assert.equal(canAccessSurface(caps, 'reports'), true);
  assert.equal(canAccessSurface(caps, 'reportExport'), true);
  assert.equal(canAccessSurface(caps, 'technicalAdmin'), true);
  assert.equal(canAccessSurface(caps, 'integrations'), true);
  assert.equal(canAccessSurface(caps, 'audit'), true);
  assert.equal(canAccessSurface(caps, 'upload'), false);
  assert.equal(canAccessSurface(caps, 'review'), false);
  assert.equal(canAccessSurface(caps, 'businessAdmin'), false);
});

test('platform operations administrator gets technical admin, audit and reports but no business workflow', () => {
  const caps = capabilitiesByRole.PLATFORM_OPERATIONS_ADMIN;
  assert.equal(canAccessSurface(caps, 'dashboard'), true);
  assert.equal(canAccessSurface(caps, 'reports'), true);
  assert.equal(canAccessSurface(caps, 'technicalAdmin'), true);
  assert.equal(canAccessSurface(caps, 'integrations'), true);
  assert.equal(canAccessSurface(caps, 'audit'), true);
  assert.equal(canAccessSurface(caps, 'upload'), false);
  assert.equal(canAccessSurface(caps, 'review'), false);
  assert.equal(canAccessSurface(caps, 'businessAdmin'), false);
});

test('internal admin UI can expose all top-level surfaces when backend returns all capabilities', () => {
  const caps = capabilitiesByRole.ADMIN;
  for (const surface of ['dashboard', 'upload', 'review', 'reports', 'reportExport', 'businessAdmin', 'integrations', 'technicalAdmin', 'audit'] as const) {
    assert.equal(canAccessSurface(caps, surface), true, surface);
  }
});
