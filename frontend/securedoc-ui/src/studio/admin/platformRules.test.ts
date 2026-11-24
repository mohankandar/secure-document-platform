import assert from 'node:assert/strict';
import test from 'node:test';
import {
  SECTION_LABELS, USAGE_MODES, eventText, intervalText, latestChecks, neverText, serviceName, serviceProduct, serviceStatus, serviceStatusText, servicesTitle,
  storageKind, storageReadiness, storageReason, storageStatus, storageUsedBy, storageValidated, usageMode, usageSummary,
} from './platformRules.ts';
import type { ProcessingStorageNodeValidation, ProcessingStorageOverview } from '../../types';

test('section names are the vocabulary words', () => {
  assert.deepEqual(Object.values(SECTION_LABELS), ['Overview', 'Processing storage', 'Branding', 'Usage data']);
});

test('services in plain words', () => {
  assert.equal(serviceName({ key: 'ocr', label: 'OCR', provider: 'tesseract' }), 'Text recognition');
  assert.equal(serviceName({ key: 'ai', label: 'Sensitive-content AI', provider: 'ollama' }), 'AI model');
  assert.equal(serviceName({ key: 'delivery', label: 'Final delivery', provider: 'document-store' }), 'Delivery to the document store');
  assert.equal(serviceName({ key: 'delivery', label: 'Final delivery', provider: 'local' }), 'Delivery to a local folder');
  assert.equal(serviceName({ key: 'new-thing', label: 'New thing', provider: '' }), 'New thing');
  assert.equal(serviceProduct({ provider: 'tesseract' }), 'Tesseract');
  assert.equal(serviceProduct({ provider: 'managed' }), '');
});

test('status words say only what the server can tell: no "Slow"', () => {
  assert.deepEqual(['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNAVAILABLE'].map((s) => serviceStatus(s).word), ['Working', 'Having problems', 'Down', 'Down']);
  assert.equal(serviceStatus('CONFIGURED').word, 'Set up');
  assert.deepEqual(serviceStatus('CONFIGURED').rest, ['not checked yet']);
  assert.equal(serviceStatus('NOT_CONFIGURED').word, 'Not set up');
  assert.equal(serviceStatus('DISABLED').word, 'Switched off');
  // Anything unexpected is never shown as working.
  for (const s of ['UP', 'SLOW', '', 'weird']) {
    assert.equal(serviceStatus(s).word, 'Status not recognised');
    assert.equal(serviceStatus(s).tone, 'warn');
  }
  for (const s of ['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'CONFIGURED', 'x']) assert.doesNotMatch(serviceStatus(s).word, /slow/i);
});

test('services title counts what needs attention', () => {
  assert.equal(servicesTitle(['HEALTHY', 'HEALTHY']), 'All services working');
  assert.equal(servicesTitle(['HEALTHY', 'DEGRADED']), '1 service needs attention');
  assert.equal(servicesTitle(['UNHEALTHY', 'NOT_CONFIGURED', 'HEALTHY']), '2 services need attention');
  assert.equal(servicesTitle(['HEALTHY', 'CONFIGURED', 'CONFIGURED']), 'No problems found · 2 not checked yet');
});

test('watched folder interval in words', () => {
  assert.equal(intervalText(30000), 'every 30 s');
  assert.equal(intervalText(60000), 'every minute');
  assert.equal(intervalText(300000), 'every 5 min');
  assert.equal(intervalText(3600000), 'every hour');
});

const v = (providerId: string, nodeId: string, status: string, minute: number, failureCode: string | null = null): ProcessingStorageNodeValidation =>
  ({ providerId, nodeId, processInstanceId: 'p1', status, failureCode, checkedAt: new Date(Date.UTC(2026, 10, 18, 12, minute)).toISOString() });

const overview = (over: Partial<ProcessingStorageOverview> = {}): ProcessingStorageOverview => ({
  ready: true,
  deploymentDefault: { id: 'a1', providerId: 'ssd', scope: 'DEPLOYMENT_DEFAULT', activatedAt: '' },
  tenantOverrides: [],
  providers: [
    { id: 'ssd', name: 'Local SSD', providerType: 'SHARED_FILESYSTEM', rootPath: 'D:\\work', status: 'AVAILABLE', configurationVersion: 1 },
    { id: 'nas', name: 'Shared NAS', providerType: 'SHARED_FILESYSTEM', rootPath: '\\\\nas01\\lv', status: 'AVAILABLE', configurationVersion: 2 },
  ],
  nodeValidations: [],
  tenants: [{ tenantId: 't1', name: 'Northwind Commercial', slug: 'nwc', status: 'ACTIVE' }, { tenantId: 't2', name: 'Contoso', slug: 'co', status: 'ACTIVE' }],
  activeNodeCount: 1,
  ...over,
});

test('storage: the latest check per server wins', () => {
  const checks = [v('nas', 'node-a', 'UNHEALTHY', 1, 'ROOT_NOT_WRITABLE'), v('nas', 'node-a', 'HEALTHY', 5)];
  assert.equal(latestChecks(checks, 'nas').length, 1);
  assert.equal(storageValidated(overview({ nodeValidations: checks }), 'nas'), true);
  assert.equal(storageStatus({ id: 'nas', status: 'AVAILABLE', providerType: 'SHARED_FILESYSTEM' }, overview({ nodeValidations: checks })).word, 'Working');
});

test('storage status in plain words', () => {
  const nas = { id: 'nas', status: 'AVAILABLE', providerType: 'SHARED_FILESYSTEM' };
  assert.equal(storageStatus(nas, overview()).word, 'Not tested yet');
  const two = overview({ activeNodeCount: 2, nodeValidations: [v('nas', 'node-a', 'HEALTHY', 1), v('nas', 'node-b', 'UNHEALTHY', 1, 'ROOT_NOT_WRITABLE')] });
  assert.deepEqual(storageStatus(nas, two), { tone: 'danger', word: 'Not working from 1 of 2 servers', rest: ["can't write here"] });
  assert.equal(storageValidated(two, 'nas'), false);
  const half = overview({ activeNodeCount: 2, nodeValidations: [v('nas', 'node-a', 'HEALTHY', 1)] });
  assert.equal(storageStatus(nas, half).word, 'Tested on 1 of 2 servers');
  assert.equal(storageStatus({ ...nas, status: 'RETIRED' }, overview()).word, 'No longer used');
  assert.equal(storageStatus({ ...nas, providerType: 'LOCAL_FILESYSTEM' }, overview({ activeNodeCount: 2 })).word, "Can't be used");
  assert.equal(storageStatus(nas, overview({ activeNodeCount: 0 })).word, 'No server has checked in');
  assert.equal(storageReason('SOMETHING_NEW'), 'the test failed');
  assert.equal(storageKind('SHARED_FILESYSTEM'), 'Shared network folder');
});

test('storage: who uses it', () => {
  const o = overview();
  assert.equal(storageUsedBy({ id: 'ssd', status: 'AVAILABLE' }, o), 'Default for every customer');
  assert.equal(storageUsedBy({ id: 'nas', status: 'AVAILABLE' }, o), 'Not used yet');
  const withOwn = overview({ tenantOverrides: [{ id: 'a2', tenantId: 't1', providerId: 'nas', scope: 'TENANT_OVERRIDE', activatedAt: '' }] });
  assert.equal(storageUsedBy({ id: 'nas', status: 'AVAILABLE' }, withOwn), 'Northwind Commercial only');
  assert.equal(storageUsedBy({ id: 'ssd', status: 'AVAILABLE' }, withOwn), 'Default for everyone else');
});

test('storage readiness line', () => {
  assert.equal(storageReadiness(overview()).title, 'Ready · 1 server');
  assert.equal(storageReadiness(overview({ ready: false, deploymentDefault: null })).title, 'Not ready · no default storage');
  const local = overview({ ready: false, activeNodeCount: 2, providers: [{ id: 'ssd', name: 'Local', providerType: 'LOCAL_FILESYSTEM', rootPath: 'C:\\x', status: 'AVAILABLE', configurationVersion: 1 }] });
  assert.match(storageReadiness(local).note, /shared network folder/);
  for (const o of [overview(), local]) assert.doesNotMatch(`${storageReadiness(o).title} ${storageReadiness(o).note}`, /LOCAL_FILESYSTEM|SHARED_FILESYSTEM|node|fail-closed/i);
});

test('usage data: modes and plain words', () => {
  assert.deepEqual(USAGE_MODES, ['Off', 'Basic', 'Detailed']);
  assert.equal(usageMode('ENHANCED').word, 'Detailed');
  assert.equal(usageMode('OFF').word, 'Off');
  assert.equal(usageMode('LOUD').known, false);
  assert.equal(eventText('package.processing.finished'), 'a case finished processing');
  assert.equal(eventText('package.new.event'), 'another event');
  assert.equal(neverText('document/PDF/image content'), 'Document content: PDFs and images');
  assert.equal(neverText('something new'), 'something new');
  const t = { mode: 'BASIC', enabled: true, eventAllowList: ['package.processing.finished', 'x.y', 'z.w'], prohibitedData: ['actor identities'] };
  assert.equal(usageSummary(t).sends, 'Sends counts of when a case finished processing, another event.');
  assert.deepEqual(usageSummary(t).never, ['Who did what: user names']);
  assert.equal(usageSummary({ ...t, mode: 'OFF' }).sends, 'Nothing is being sent.');
  assert.equal(usageSummary({ ...t, enabled: false }).sends, 'Nothing is being sent.');
});

test('task 0026: a failed health check shows its code, LV-SYS-006; other statuses show none', () => {
  assert.equal(serviceStatusText('UNHEALTHY', 'LV-SYS-006'), 'Down · LV-SYS-006');
  assert.equal(serviceStatusText('HEALTHY', null), 'Working');
  assert.equal(serviceStatusText('CONFIGURED'), 'Set up · not checked yet');
});
