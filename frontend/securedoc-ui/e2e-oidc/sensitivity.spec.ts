import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * Task 0021: sensitivity levels on screen. The source system sets a level; LexaVault keeps it, passes it on and never
 * changes it, and it limits nothing. So it shows only as one read-only line in the case panel (and a document's own
 * level when it differs from the case's), never as a badge in Cases; New case has a "Sensitivity" choice.
 *
 * Same harness as newCase.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider, API and scan
 * bridge mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c6c6c6c6-0000-4000-8000-00000000000c', name: 'Level Entity', slug: 'level-entity', roles: ['DOCUMENT_SUPERVISOR'] };
const CAPS = capabilitiesFor(...ENTITY.roles);
const BRIDGE = 'http://127.0.0.1:10090/api/v1';

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'level-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'level-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'level-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const LOAN = {
  key: 'loan_number', label: 'Loan number', required: true, primary: true, searchable: true, extractable: false, displayable: true, displayOrder: 1,
  validationType: 'ALPHANUMERIC', caseNormalization: 'UPPER', stripSeparators: false, allowedValues: [],
};

const CASE = {
  packageId: 'c6c6c6c6-0000-4000-8000-000000000001', tenantId: ENTITY.tenantId, packageType: 'API_SUBMISSION', status: 'PENDING_FINAL_APPROVAL',
  documentCount: 2, createdAt: '2026-11-20T12:00:00Z', updatedAt: '2026-11-20T12:00:00Z',
  businessIdentifiers: [{ key: 'loan_number', label: 'Loan number', value: 'LN-7001', primary: true, searchable: true, displayable: true, displayOrder: 1 }],
  sensitivityLevel: 'CONFIDENTIAL', effectiveSensitivityLevel: 'HIGHLY_CONFIDENTIAL',
  documents: [
    { documentId: 'd-1', fileName: 'Note.pdf', mimeType: 'application/pdf', sizeBytes: 2048, status: 'PROCESSED',
      sensitivityLevel: 'CONFIDENTIAL', effectiveSensitivityLevel: 'CONFIDENTIAL' },
    { documentId: 'd-2', fileName: 'Medical.pdf', mimeType: 'application/pdf', sizeBytes: 4096, status: 'PROCESSED',
      sensitivityLevel: 'HIGHLY_CONFIDENTIAL', effectiveSensitivityLevel: 'HIGHLY_CONFIDENTIAL' },
  ],
};

type Sent = { method: string; path: string; body: Record<string, unknown> };

async function mockApi(page: Page, sent: Sent[]) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (request.method() !== 'GET') sent.push({ method: request.method(), path: url.pathname, body: JSON.parse(request.postData() || '{}') });
    if (url.origin === new URL(BRIDGE).origin) {
      if (url.pathname.endsWith('/scanner-status')) {
        return json({
          enabled: true, bridgeId: 'BRIDGE-LOCAL-01', siteId: 'HOME-LAB', scannerId: 'SCANNER-SIM-01', captureMode: 'SIMULATOR',
          bridgeInstanceId: 'i-1', bridgeVersion: '1', acquisitionAvailable: true, storageHealthy: true, usableSpaceBytes: 80 * 1024 ** 3,
          minimumUsableSpaceBytes: 1, maxScanPdfSizeBytes: 1, busy: false, activeSessionId: null, queuedCount: 0, attentionRequiredCount: 0,
          checkedAt: '2026-11-20T12:00:00Z',
        });
      }
      if (url.pathname.endsWith('/scan-sessions') && request.method() === 'POST') return json({ code: 'TEST_STOP', message: 'stopped by the test' }, 503);
      return route.fulfill({ status: 204, headers: cors });
    }
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'level.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: CAPS });
    }
    if (url.pathname === '/api/v1/intake/identifier-definitions') return json([LOAN]);
    if (url.pathname === '/api/v1/packages' && request.method() === 'POST') return json({ code: 'TEST_STOP', message: 'stopped by the test' }, 503);
    if (url.pathname === '/api/v1/packages') return json([CASE]);
    if (url.pathname === `/api/v1/packages/${CASE.packageId}`) return json(CASE);
    if (url.pathname.includes('/lifecycle')) return json({ packageId: CASE.packageId, successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: false, finalApprovalRequired: true, finalApprovalGranted: false });
    if (url.pathname.startsWith('/api/v1/review-items')) return json({ totalItems: 0, items: [] });
    return json({});
  });
}

async function signIn(page: Page, sent: Sent[] = []) {
  await mockIdentityProvider(page);
  await mockApi(page, sent);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('link', { name: /^New case/ }).first()).toBeVisible();
}

const row = (page: Page) => page.locator('li.cf-case-row').filter({ hasText: 'LN-7001' });

test('the case panel shows one read-only line with the effective level, and a document level that differs', async ({ page }) => {
  await signIn(page);
  await row(page).getByRole('link').click();
  const panel = page.getByRole('complementary', { name: 'Case panel' });
  await expect(panel.getByTestId('case-sensitivity')).toHaveText('Sensitivity: Highly Confidential, as sent by the source system');
  await expect(panel.getByText(/^Sensitivity:/)).toHaveCount(1);
  const docs = panel.locator('li.cf-doc');
  // Note.pdf has the case's own level, so nothing is added; Medical.pdf differs, so its level is shown next to it.
  await expect(docs.filter({ hasText: 'Note.pdf' }).getByTestId('document-sensitivity')).toHaveCount(0);
  await expect(docs.filter({ hasText: 'Medical.pdf' }).getByTestId('document-sensitivity')).toHaveText('Highly Confidential');
  // Read-only: nothing in the panel lets anyone change a level.
  await expect(panel.getByRole('combobox', { name: /sensitivity/i })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: /sensitivity/i })).toHaveCount(0);
});

test('no badge in the Cases list: the level limits nothing in LexaVault', async ({ page }) => {
  await signIn(page);
  await expect(row(page)).toBeVisible();
  await expect(row(page)).not.toContainText(/confidential|sensitivity/i);
  await expect(page.locator('main')).not.toContainText(/confidential/i);
});

test('New case has a Sensitivity choice, Standard by default, and Upload sends it as the case level', async ({ page }) => {
  const sent: Sent[] = [];
  await signIn(page, sent);
  await page.goto('/intake');
  const choice = page.getByLabel('Sensitivity');
  await expect(choice).toHaveValue('STANDARD');
  expect(await choice.locator('option').allTextContents()).toEqual(['Standard', 'Confidential', 'Highly Confidential']);
  await page.getByLabel(/Loan number/).fill('LN204901');
  await choice.selectOption({ label: 'Confidential' });
  await page.locator('input[type=file]').setInputFiles([{ name: 'Note.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n', 'latin1') }]);
  await page.getByRole('button', { name: 'Create case' }).click();
  await expect.poll(() => sent.find((s) => s.method === 'POST' && s.path === '/api/v1/packages')?.body).toMatchObject({
    packageType: 'MANUAL_UPLOAD', sensitivityLevel: 'CONFIDENTIAL',
  });
});

test('Scan paper sends the Sensitivity choice to the scan bridge', async ({ page }) => {
  const sent: Sent[] = [];
  await signIn(page, sent);
  await page.goto('/intake?via=scan');
  await page.getByLabel(/Loan number/).fill('LN204902');
  await page.getByLabel('Sensitivity').selectOption({ label: 'Highly Confidential' });
  await page.getByRole('button', { name: 'Start scan session' }).click();
  await expect.poll(() => sent.find((s) => s.method === 'POST' && s.path.endsWith('/scan-sessions'))?.body).toMatchObject({
    caseSensitivityLevel: 'HIGHLY_CONFIDENTIAL',
  });
});
