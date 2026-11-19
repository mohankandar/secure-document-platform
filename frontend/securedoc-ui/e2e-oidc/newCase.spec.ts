import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U005: New case states the upload rules, lists each file with size and pages, marks a file the server would
 * refuse in red with what to do (Create case stays disabled), reads ?id= for the primary identifier, and names the
 * scanner by its site with the device details under Technical details.
 *
 * Same harness as caseRows.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider, API and scan
 * bridge mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c5c5c5c5-0000-4000-8000-00000000000c', name: 'Intake Entity', slug: 'intake-entity', roles: ['DOCUMENT_PROCESSOR'] };
const PROCESSOR = capabilitiesFor(...ENTITY.roles);
const BRIDGE = 'http://127.0.0.1:10090/api/v1';

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

const LOAN = {
  key: 'loan_number', label: 'Loan number', required: true, primary: true, searchable: true, extractable: false, displayable: true, displayOrder: 1,
  validationType: 'ALPHANUMERIC', caseNormalization: 'UPPER', stripSeparators: false, allowedValues: [],
};

async function mockApi(page: Page, calls: string[]) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.origin === new URL(BRIDGE).origin) {
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
      if (url.pathname.endsWith('/scanner-status')) {
        return json({
          enabled: true, bridgeId: 'BRIDGE-LOCAL-01', siteId: 'HOME-LAB', scannerId: 'SCANNER-SIM-01', captureMode: 'SIMULATOR',
          bridgeInstanceId: 'i-1', bridgeVersion: '1', acquisitionAvailable: true, storageHealthy: true, usableSpaceBytes: 80 * 1024 ** 3,
          minimumUsableSpaceBytes: 1, maxScanPdfSizeBytes: 1, busy: false, activeSessionId: null, queuedCount: 0, attentionRequiredCount: 0,
          checkedAt: '2026-11-18T12:00:00Z',
        });
      }
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() !== 'GET') calls.push(`${request.method()} ${url.pathname}`);
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'intake.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: PROCESSOR });
    }
    if (url.pathname === '/api/v1/intake/identifier-definitions') return json([LOAN]);
    if (url.pathname === '/api/v1/packages') return json([]);
    return json({});
  });
}

async function openNewCase(page: Page, path = '/intake', calls: string[] = []) {
  await mockIdentityProvider(page);
  await mockApi(page, calls);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('link', { name: /^New case/ }).first()).toBeVisible();
  await page.goto(path);
  await expect(page.getByRole('heading', { name: 'New case', level: 1 })).toBeVisible();
}

function twoPagePdf(): Buffer {
  return Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n'
    + '3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n4 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n', 'latin1');
}

const createButton = (page: Page) => page.getByRole('button', { name: 'Create case' });

test('states the rules, lists size and pages, and holds Create case while a file the server would refuse is listed', async ({ page }) => {
  const calls: string[] = [];
  await openNewCase(page, '/intake', calls);
  await expect(page.getByText('PDF only · up to 50 MB each')).toBeVisible();
  // U019: one line "Drop PDFs here or choose files", the rules under it, no separate order note.
  await expect(page.locator('.cf-drop-line')).toHaveText('Drop PDFs here or choose files');
  await expect(page.getByText(/processed in the order/)).toHaveCount(0);
  await expect(page.getByText('Upload PDFs you already have, or scan paper.')).toBeVisible();
  await expect(page.locator('.cf-intake')).not.toContainText(/\bvault\b/i); // "LexaVault" is the product name
  await expect(page.getByRole('button', { name: /Open case & submit/ })).toHaveCount(0);

  await page.getByLabel(/Loan number/).fill('LN204901');
  await page.locator('input[type=file]').setInputFiles([
    { name: 'Closing_Disclosure.pdf', mimeType: 'application/pdf', buffer: twoPagePdf() },
    { name: 'scanned_batch_14.tif', mimeType: 'image/tiff', buffer: Buffer.from('II*\0 not a pdf') },
  ]);

  const rows = page.locator('.cf-ledger-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText('2 pages');
  await expect(rows.nth(0)).not.toContainText('Not a PDF');
  await expect(rows.nth(1)).toHaveAttribute('data-state', 'refused');
  await expect(rows.nth(1)).toContainText('Not a PDF — remove it or convert it first');
  await expect(page.getByText('Fix the file marked in red to continue.')).toBeVisible();
  await expect(createButton(page)).toBeDisabled();

  await page.getByRole('button', { name: 'Remove scanned_batch_14.tif' }).click();
  await expect(rows).toHaveCount(1);
  await expect(createButton(page)).toBeEnabled();
  expect(calls).toEqual([]); // nothing was sent while the file was listed
});

test('a .pdf file that is not a PDF inside is marked too', async ({ page }) => {
  await openNewCase(page);
  await page.getByLabel(/Loan number/).fill('LN204901');
  await page.locator('input[type=file]').setInputFiles([{ name: 'renamed.pdf', mimeType: 'application/pdf', buffer: Buffer.from('PK\u0003\u0004 zip') }]);
  await expect(page.locator('.cf-ledger-row')).toContainText('Not a PDF — remove it or convert it first');
  await expect(createButton(page)).toBeDisabled();
});

test('?id= fills the primary identifier', async ({ page }) => {
  await openNewCase(page, '/intake?id=LN204901');
  await expect(page.getByLabel(/Loan number/)).toHaveValue('LN204901');
});

test('the Scan tab names the scanner by its site; device, site and free space are under Technical details', async ({ page }) => {
  await openNewCase(page, '/intake?via=scan');
  await expect(page.locator('.cf-scanner-name')).toHaveText('Scanner at HOME-LAB');
  await expect(page.locator('.cf-scanner')).not.toContainText('SCANNER-SIM-01');
  const details = page.locator('details', { hasText: 'Technical details' });
  await expect(details.getByText('SCANNER-SIM-01')).toBeHidden();
  await details.locator('summary').click();
  await expect(details).toContainText('Device ID');
  await expect(details).toContainText('SCANNER-SIM-01');
  await expect(details).toContainText('Site ID');
  await expect(details).toContainText('Free space');
  await expect(page.getByRole('button', { name: 'Start scan session' })).toBeVisible();
});
