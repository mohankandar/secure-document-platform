import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * Task 0061: case fields of every type on New case and under Admin, sensitive fields, and what is sent. Same harness as
 * newCase.spec.ts; identity provider, API and scan bridge mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c5c5c5c5-0000-4000-8000-00000000000c', name: 'Intake Entity', slug: 'intake-entity', roles: ['DOCUMENT_PROCESSOR'] };
const PROCESSOR = capabilitiesFor(...ENTITY.roles);
const BRIDGE = 'http://127.0.0.1:10090/api/v1';
const PROOF = process.env.LV_0061_PROOF;

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'intake-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

async function mockIdentityProvider(page: Page) {
  await page.route(`${ISSUER}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        issuer: ISSUER, authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
        token_endpoint: `${ISSUER}/protocol/openid-connect/token`, end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
      }) });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/auth')) {
      const back = new URL(url.searchParams.get('redirect_uri') || '/');
      back.searchParams.set('code', 'intake-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'intake-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const base = { required: false, primary: false, searchable: true, extractable: false, displayable: true, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [] as string[], searchMode: 'EXACT', sensitive: false, multiValue: false };
const FIELDS = [
  { ...base, id: 'f1', key: 'loan_number', label: 'Loan number', required: true, primary: true, displayOrder: 1, validationType: 'NUMBER', customPattern: '[0-9]+', exactLength: 6, locked: true },
  { ...base, id: 'f2', key: 'branch', label: 'Branch', displayOrder: 2, validationType: 'DROPDOWN', allowedValues: ['North', 'South'] },
  { ...base, id: 'f3', key: 'amount', label: 'Amount', displayOrder: 3, validationType: 'MONEY', currency: 'USD' },
  { ...base, id: 'f4', key: 'opened', label: 'Opened', displayOrder: 4, validationType: 'DATE' },
  { ...base, id: 'f5', key: 'property', label: 'Property address', displayOrder: 5, validationType: 'ADDRESS', sensitive: true },
  { ...base, id: 'f6', key: 'borrower_name', label: 'Borrower name', displayOrder: 6, validationType: 'TEXT', sensitive: true, multiValue: true },
];
const SCAN = { sessionId: 's-1', submissionId: 'b-1', status: 'WAITING_FOR_SCAN', captureMode: 'SIMULATOR', createdAt: '2026-11-18T12:00:00Z', updatedAt: '2026-11-18T12:00:00Z', deliveryAttemptCount: 0 };

const ADMIN = { tenantId: ENTITY.tenantId, name: ENTITY.name, slug: ENTITY.slug, roles: ['BUSINESS_ADMIN'] };
const configuration = () => ({
  identifiers: FIELDS, switchedOffIdentifiers: [{ ...base, id: 'f9', key: 'old_ref', label: 'Old reference', displayOrder: 9, validationType: 'TEXT' }],
  sensitiveFieldsAvailable: true, dashboardColumns: [],
  sensitiveDataPolicy: { enabledEntityTypes: ['SSN'], autoRedactThreshold: 0.8, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true },
  lifecyclePolicy: { originalRetentionHours: 24, redactedCacheHours: 24, neverExportedHoldHours: 72, finalApprovalRequired: false, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 72 },
  documentIntelligence: { mode: 'DISABLED', activeBundleVersion: '1', configurationVersion: 1, baselineDiscoveryRequired: true, availableBundles: [] },
  reportingConfiguration: { dimensions: [], deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 90, reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80 },
});

interface Sent { method: string; path: string; search: string; body: any }

async function open(page: Page, path: string, who: typeof ENTITY, sent: Sent[]) {
  await mockIdentityProvider(page);
  const cases = [{ packageId: 'p-1', packageType: 'MANUAL_UPLOAD', status: 'PROCESSING', documentCount: 1, createdAt: '2026-11-18T12:00:00Z', updatedAt: '2026-11-18T12:00:00Z', businessIdentifiers: [
    { key: 'loan_number', label: 'Loan number', value: '204860', primary: true, searchable: true, displayable: true, displayOrder: 1, sensitive: false },
    { key: 'borrower_name', label: 'Borrower name', value: String.fromCharCode(0x2022).repeat(6), primary: false, searchable: true, displayable: true, displayOrder: 6, sensitive: true }] }];
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
    if (request.method() !== 'GET' && request.method() !== 'OPTIONS') {
      const text = request.postData();
      let body: any = null;
      try { body = text ? JSON.parse(text) : null; } catch { body = null; }
      sent.push({ method: request.method(), path: url.pathname, search: url.search, body });
    }
    if (url.origin === new URL(BRIDGE).origin) {
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
      if (url.pathname.endsWith('/scanner-status')) return json({ enabled: true, bridgeId: 'B', siteId: 'HOME-LAB', scannerId: 'S', captureMode: 'SIMULATOR', bridgeInstanceId: 'i', bridgeVersion: '1', acquisitionAvailable: true, storageHealthy: true, usableSpaceBytes: 1e11, minimumUsableSpaceBytes: 1, maxScanPdfSizeBytes: 1, busy: false, activeSessionId: null, queuedCount: 0, attentionRequiredCount: 0, checkedAt: '2026-11-18T12:00:00Z' });
      if (url.pathname.endsWith('/configuration')) return json({ requestToken: 't', enabled: true, bridgeId: 'B', siteId: 'HOME-LAB', scannerId: 'S', captureMode: 'SIMULATOR' });
      if (url.pathname.endsWith('/scan-sessions') && request.method() === 'POST') return json({ ...SCAN, businessIdentifiers: {} });
      return route.fulfill({ status: 204, headers: cors });
    }
    if (url.pathname === '/api/v1/me/tenants') return json([who]);
    if (url.pathname === '/api/v1/me') return json({ username: 'field.user', roles: who.roles, tenantName: who.name, tenantSlug: who.slug, authenticationMode: 'OIDC', capabilities: capabilitiesFor(...who.roles) });
    if (url.pathname === '/api/v1/intake/identifier-definitions') return json(FIELDS);
    if (url.pathname === '/api/v1/business-admin/configuration' || url.pathname === '/api/v1/business-admin/identifiers') return json(configuration());
    if (url.pathname === '/api/v1/operations/dashboard') { sent.push({ method: 'GET', path: url.pathname, search: url.searchParams.get('search') ?? '', body: null }); return json({ items: [{ packageId: 'p-1' }], columns: [], totalItems: 1, offset: 0, limit: 100 }); }
    if (url.pathname === '/api/v1/packages' && request.method() === 'POST') return json({ packageId: 'p-1', status: 'CREATED' });
    if (url.pathname === '/api/v1/packages') return json(cases);
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('navigation').first()).toBeVisible();
  await page.goto(path);
}

async function fill(page: Page) {
  await page.getByLabel('Loan number').fill('204860');
  await page.getByLabel('Branch').selectOption('South');
  await page.getByLabel('Amount').fill('1,250.50');
  await page.getByLabel('Opened').fill('2026-11-21');
  for (const [part, value] of [['Street', '1 Elm Street'], ['City', 'Springfield'], ['State', 'il'], ['ZIP', '62701']] as const) {
    await page.getByLabel(`Property address: ${part}`, { exact: true }).fill(value);
  }
  await page.getByLabel('Borrower name').first().fill('Synthetic Quill');
  await page.getByRole('button', { name: 'Add another' }).click();
  await page.getByLabel('Borrower name 2', { exact: true }).fill('Synthetic Heron');
}

test('New case takes every type and sends each field as the server reads it', async ({ page }) => {
  const sent: Sent[] = [];
  await open(page, '/intake', ENTITY, sent);
  await expect(page.getByText('Sensitive').first()).toBeVisible();
  await fill(page);
  if (PROOF) {
    await page.setViewportSize({ width: 1280, height: 1180 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${PROOF}/new-case.png` });
  }
  await page.getByLabel('Choose PDF files').setInputFiles({ name: 'one.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n', 'latin1') });
  await page.getByRole('button', { name: 'Create case' }).click();
  await expect.poll(() => sent.find((s) => s.path === '/api/v1/packages')?.body?.businessIdentifiers).toEqual({
    loan_number: '204860', branch: 'South', amount: '1,250.50', opened: '2026-11-21',
    property: ['1 Elm Street', '', 'Springfield', 'IL', '62701', ''].join(String.fromCharCode(0x1f)),
    borrower_name: ['Synthetic Quill', 'Synthetic Heron'].join(String.fromCharCode(0x1e)),
  });
});

test('a value that breaks its rule is said before anything is sent', async ({ page }) => {
  await open(page, '/intake', ENTITY, []);
  await page.getByLabel('Loan number').fill('12');
  await page.getByLabel('Property address: Street', { exact: true }).fill('1 Elm Street');
  await page.getByLabel('Amount').fill('much');
  await page.getByLabel('Amount').blur();
  await expect(page.getByText('Enter an amount')).toBeVisible();
  await page.getByLabel('Property address: Street', { exact: true }).blur();
  await expect(page.getByText('Enter the street and city')).toBeVisible();
});

test('a scan names the sensitive fields, so the Scan Bridge keeps them in memory only', async ({ page }) => {
  const sent: Sent[] = [];
  await open(page, '/intake?via=scan', ENTITY, sent);
  await fill(page);
  await page.getByRole('button', { name: /start|scan/i }).last().click();
  await expect.poll(() => sent.find((s) => s.path.endsWith('/scan-sessions'))?.body?.sensitiveFields).toEqual(['property', 'borrower_name']);
});

test('Cases hides a sensitive value and finds it by the whole value, through the server', async ({ page }) => {
  const sent: Sent[] = [];
  await open(page, '/', ENTITY, sent);
  await expect(page.getByText('204860').first()).toBeVisible();
  await expect(page.getByText('Synthetic')).toHaveCount(0);
  await page.locator('main input[placeholder]').first().fill('Synthetic Quill');
  await expect.poll(() => sent.some((s) => s.path === '/api/v1/operations/dashboard' && s.search === 'Synthetic Quill')).toBe(true);
  await expect(page.getByText('204860').first()).toBeVisible();
  if (PROOF) await page.screenshot({ path: `${PROOF}/cases.png` });
});

test('Admin sets up every type, marks a name sensitive, locks a field with values and switches one on again', async ({ page }) => {
  const sent: Sent[] = [];
  await open(page, '/admin/fields', { ...ADMIN }, sent);
  await expect(page.getByRole('heading', { name: 'Case fields' }).first()).toBeVisible();
  await expect(page.getByText('US address · sensitive').first()).toBeVisible();
  await expect(page.getByText('Amount in USD')).toBeVisible();
  if (PROOF) await page.screenshot({ path: `${PROOF}/admin-fields.png`, fullPage: true });

  await page.getByRole('button', { name: 'Edit Loan number' }).click();
  await expect(page.getByLabel('What it can contain')).toBeDisabled();
  await expect(page.getByText(/This field has values/)).toBeVisible();
  if (PROOF) await page.screenshot({ path: `${PROOF}/admin-field-locked.png` });
  await page.getByRole('button', { name: 'Cancel' }).click();

  await page.getByRole('button', { name: 'Add case field' }).click();
  const kinds = page.getByLabel('What it can contain');
  await expect(kinds.locator('option')).toHaveCount(13);
  const sheet = page.getByRole('dialog');
  await sheet.getByRole('textbox', { name: /^Name/ }).fill('Agent name');
  await expect(sheet.getByLabel('Sensitive', { exact: true }).or(sheet.getByRole('switch', { name: /^Sensitive/ })).first()).toBeChecked();
  await expect(page.getByText('A sensitive value is found by the whole value only.')).toBeVisible();
  await kinds.selectOption('MONEY');
  await expect(page.getByLabel('Currency')).toHaveValue('USD');
  await kinds.selectOption('LETTERS_DIGITS');
  if (PROOF) await page.screenshot({ path: `${PROOF}/admin-field-new.png` });
  await page.getByRole('button', { name: 'Add to list' }).click();
  await page.getByRole('button', { name: 'Switch on Old reference' }).click();
  await page.getByRole('button', { name: 'Save changes' }).first().click();
  await expect.poll(() => sent.find((s) => s.path === '/api/v1/business-admin/identifiers')?.body?.identifiers?.length).toBe(8);
  const saved = sent.find((s) => s.path === '/api/v1/business-admin/identifiers')!.body.identifiers;
  expect(saved[6]).toMatchObject({ label: 'Agent name', validationType: 'TEXT', customPattern: '[A-Za-z0-9]+', sensitive: true, searchMode: 'EXACT' });
  expect(saved[7]).toMatchObject({ id: 'f9', label: 'Old reference' });
});
