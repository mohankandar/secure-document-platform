import assert from 'node:assert/strict';
import test from 'node:test';
import { POLICY_CAPABILITIES, capabilitiesFor } from '../../e2e-oidc/policyCapabilities.ts';
import { ROLE_OPTIONS, canAccessSurface } from './uiAuthorization.ts';

// The lists the backend grants (ProductAuthorizationPolicy), from the one shared copy that
// policyCapabilities.test.ts keeps in step with the Java file (U018 follow-up, owner's answer 5).
type Surface = Parameters<typeof canAccessSurface>[1];
const ALL_SURFACES: Surface[] = ['dashboard', 'upload', 'review', 'reports', 'reportExport', 'businessAdmin', 'integrations', 'technicalAdmin', 'audit'];

/** Checks every surface: the ones listed are open, every other one is closed. */
function assertSurfaces(role: string, open: Surface[]) {
  const caps = capabilitiesFor(role);
  for (const surface of ALL_SURFACES) {
    assert.equal(canAccessSurface(caps, surface), open.includes(surface), `${role}: ${surface}`);
  }
}

test('demo selector exposes canonical product roles only', () => {
  assert.deepEqual(ROLE_OPTIONS.map((option) => option.value), [
    'DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'BUSINESS_ADMIN', 'REPORT_VIEWER',
    'TENANT_IT_ADMIN', 'PLATFORM_OPERATIONS_ADMIN',
  ]);
  assert.deepEqual(ROLE_OPTIONS.map((option) => option.value).sort(), Object.keys(POLICY_CAPABILITIES).sort());
});

test('document processor: business workflow, no reports and no admin', () => {
  assertSurfaces('DOCUMENT_PROCESSOR', ['dashboard', 'upload', 'review']);
});

test('document supervisor keeps the processor surfaces', () => {
  assertSurfaces('DOCUMENT_SUPERVISOR', ['dashboard', 'upload', 'review']);
});

test('business administrator: reports, export and business admin; no case work', () => {
  assertSurfaces('BUSINESS_ADMIN', ['reports', 'reportExport', 'businessAdmin']);
});

test('report viewer is reports-only and cannot export', () => {
  assertSurfaces('REPORT_VIEWER', ['reports']);
});

test('tenant IT administrator: integrations, technical admin and audit; no cases and no reports', () => {
  assertSurfaces('TENANT_IT_ADMIN', ['integrations', 'technicalAdmin', 'audit']);
});

test('platform operations administrator: technical admin and audit only', () => {
  assertSurfaces('PLATFORM_OPERATIONS_ADMIN', ['technicalAdmin', 'audit']);
});

test('every top-level surface is reachable when /me sends every role\'s capabilities', () => {
  assertSurfaces('', []);
  const caps = capabilitiesFor(...Object.keys(POLICY_CAPABILITIES));
  for (const surface of ALL_SURFACES) assert.equal(canAccessSurface(caps, surface), true, surface);
});
