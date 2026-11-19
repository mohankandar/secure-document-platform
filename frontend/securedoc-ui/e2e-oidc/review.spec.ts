import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';
import { crc32, deflateSync } from 'node:zlib';

/**
 * UI task U004: Review shows the text found (in full only in the decision panel, masked in the queue), says why a
 * person is asked with the policy level, zooms, and "Redact all like this" confirms each finding with its own call.
 *
 * Same harness as caseRows.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c4c4c4c4-0000-4000-8000-00000000000c', name: 'Review Entity', slug: 'review-entity', roles: ['DOCUMENT_PROCESSOR'] };
const PROCESSOR = capabilitiesFor(...ENTITY.roles);

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'review-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'review-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'review-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

/** A blank page the size the backend renders a US Letter page at (144 DPI): 1224 × 1584. */
function blankPagePng(width = 1224, height = 1584): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([length, body, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = 0; // 8-bit greyscale
  const rows = Buffer.alloc((width + 1) * height, 0xff);
  for (let y = 0; y < height; y += 1) rows[y * (width + 1)] = 0; // filter byte per row
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const PAGE_PNG = blankPagePng();

const CASE_A = caseFor('LN204881', '0001', [{ key: 'borrower_name', label: 'Borrower name', value: 'Maria Alvarez', primary: false, searchable: true, displayable: true, displayOrder: 2 }]);
const CASE_B = caseFor('LN204885', '0002', [{ key: 'borrower_name', label: 'Borrower name', value: 'Daniel Kim', primary: false, searchable: true, displayable: false, displayOrder: 2 }]);

function caseFor(loan: string, n: string, more: object[] = []) {
  return {
    packageId: `c4c4c4c4-0000-4000-8000-00000000${n}`, tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status: 'PENDING_REVIEW',
    documentCount: 1, createdAt: '2026-11-18T12:00:00Z', updatedAt: '2026-11-18T12:00:00Z', documents: [],
    businessIdentifiers: [{ key: 'loan_number', label: 'Loan number', value: loan, primary: true, searchable: true, displayable: true, displayOrder: 1 }, ...more],
  };
}

const BOX = { left: 0.2, top: 0.3, width: 0.2, height: 0.03 };
const finding = (id: string, pkg: string, type: string, text: string | null, hasBox = true) => ({
  findingId: id, packageId: pkg, documentId: `doc-${pkg.slice(-1)}`, fileName: 'Closing_Disclosure.pdf', entityType: type,
  confidence: 0.62, pageNumber: 1, hasBoundingBox: hasBox, boundingBox: hasBox ? BOX : null, reason: hasBox ? 'LOW_CONFIDENCE' : 'LOCATION_UNCERTAIN',
  foundText: text === null ? null : { text, before: 'Borrower SSN: ', after: '' },
});

function queueItems() {
  return [
    finding('f1', CASE_A.packageId, 'SSN', '412-55-0193'),
    finding('f2', CASE_A.packageId, 'SSN', '518-22-7741'),
    finding('f3', CASE_A.packageId, 'US_SSN', '623-11-0042', false), // same type (alias), place not found
    finding('f4', CASE_A.packageId, 'DATE_OF_BIRTH', '03/14/1979'),
    finding('f5', CASE_B.packageId, 'SSN', '701-33-9184'), // same type, other case
    finding('f6', CASE_B.packageId, 'CREDIT_SCORE', '712'), // shorter than 8: masked in full
  ];
}

async function mockApi(page: Page, confirmed: string[], dismissed: string[], initial = queueItems()) {
  let items = initial;
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'review.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: PROCESSOR });
    }
    if (url.pathname === '/api/v1/packages') return json([CASE_A, CASE_B]);
    const decision = url.pathname.match(/^\/api\/v1\/review-items\/([^/]+)\/(confirm|dismiss)$/);
    if (decision && request.method() === 'POST') {
      (decision[2] === 'confirm' ? confirmed : dismissed).push(decision[1]);
      const item = items.find((i) => i.findingId === decision[1])!;
      items = items.filter((i) => i.findingId !== decision[1]);
      return json({ findingId: decision[1], packageId: item.packageId, status: decision[2] === 'confirm' ? 'CONFIRMED' : 'DISMISSED', processingResumed: false });
    }
    if (url.pathname.endsWith('/page-preview')) {
      return route.fulfill({ status: 200, contentType: 'image/png', headers: { 'X-Review-Page-Number': '1' }, body: PAGE_PNG });
    }
    if (url.pathname === '/api/v1/review-items') return json({ totalItems: items.length, items, autoRedactThreshold: 0.85 });
    return json({});
  });
}

async function openReview(page: Page, confirmed: string[] = [], dismissed: string[] = [], items = queueItems()) {
  await mockIdentityProvider(page);
  await mockApi(page, confirmed, dismissed, items);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('link', { name: /^Review/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Review', level: 1 })).toBeVisible();
}

const queue = (page: Page) => page.getByRole('navigation', { name: 'Findings queue' });
const panel = (page: Page) => page.getByRole('complementary', { name: 'Decision' });

test('the full text is only in the decision panel; queue items show only the last 4, and short values not at all', async ({ page }) => {
  await openReview(page);
  await expect(panel(page)).toContainText('Text found');
  await expect(panel(page)).toContainText('412-55-0193');
  await expect(panel(page)).toContainText('“Borrower SSN: 412-55-0193”');

  await expect(queue(page)).toContainText('••••0193');
  await expect(queue(page)).toContainText('••••7741');
  for (const full of ['412-55-0193', '518-22-7741', '623-11-0042', '03/14/1979', '701-33-9184']) {
    await expect(queue(page)).not.toContainText(full);
  }
  const score = queue(page).locator('.cf-queue-item').filter({ hasText: 'Credit score' });
  await expect(score.locator('.cf-queue-found')).toHaveText('••••');
  await expect(score).not.toContainText('712');
  await expect(queue(page)).toContainText('place not found');

  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(stored).not.toContain('0193');
});

test('the queue case line is the Cases row line: main ID, then displayable fields only', async ({ page }) => {
  await openReview(page);
  const titles = queue(page).locator('.cf-queue-case-title');
  await expect(titles).toHaveText(['LN204881 · Maria Alvarez', 'LN204885']);
  await expect(queue(page)).not.toContainText('Daniel Kim');
});

test('says why a person is asked, with the policy level', async ({ page }) => {
  await openReview(page);
  await expect(panel(page)).toContainText('LexaVault is 62% sure this is sensitive. Your policy redacts automatically from 85%, so a person decides.');
});

test('a finding without text has no "Text found" row', async ({ page }) => {
  await openReview(page, [], [], [finding('f9', CASE_A.packageId, 'CREDIT_SCORE', null)]);
  await expect(panel(page)).toContainText('Credit score');
  await expect(panel(page)).not.toContainText('Text found');
});

test('Redact all like this: confirms first; same type and same case only, one call each, unplaced ones skipped and kept', async ({ page }) => {
  const confirmed: string[] = [];
  await openReview(page, confirmed);
  await expect(panel(page)).toContainText('2 more Social Security Numbers are waiting in this case.');
  await expect(panel(page)).toContainText('1 of them has no place on the page and will be skipped.');

  // Shift+R asks first; Cancel sends nothing.
  await page.keyboard.press('Shift+R');
  const dialog = page.getByRole('dialog', { name: 'Redact 2 Social Security Number findings?' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog).toBeHidden();
  expect(confirmed).toEqual([]);

  // The button asks too; Redact runs it.
  await panel(page).getByRole('button', { name: /Redact all 3 like this/ }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Redact', exact: true }).click();
  await expect(page.locator('.cf-toast')).toContainText('Redacted 2 findings. 1 skipped because its place was not found; it stays in the queue.');
  expect(confirmed.sort()).toEqual(['f1', 'f2']);

  await expect(queue(page)).toContainText('place not found');
  await expect(queue(page)).toContainText('Date of birth');
  await expect(queue(page).locator('.cf-queue-item')).toHaveCount(4); // f3, f4, f5, f6 remain
});

test('single Redact stays without confirmation', async ({ page }) => {
  const confirmed: string[] = [];
  await openReview(page, confirmed);
  await page.keyboard.press('r');
  await expect.poll(() => confirmed).toEqual(['f1']);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('U019: an empty queue shows no "decided this session" meter', async ({ page }) => {
  await openReview(page, [], [], []);
  await expect(page.getByText('The queue is clear.')).toBeVisible();
  await expect(page.locator('.cf-review-meter')).toHaveCount(0);
  await expect(page.getByText(/decided this session/)).toHaveCount(0);
});

test('there is no "Keep visible all"', async ({ page }) => {
  await openReview(page);
  await expect(panel(page).getByRole('button', { name: /Redact all 3 like this/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /keep visible all|keep all/i })).toHaveCount(0);
});

test('zoom: minus, percentage, plus and Fit', async ({ page }) => {
  await openReview(page);
  const value = page.locator('.cf-zoom-value');
  await expect(value).toHaveText(/^\d+%$/);
  const fit = Number((await value.textContent())!.replace('%', ''));
  await page.getByRole('button', { name: 'Zoom in' }).click();
  const zoomed = Number((await value.textContent())!.replace('%', ''));
  expect(zoomed).toBeGreaterThan(fit);
  const width = await page.locator('.cf-page').evaluate((el) => el.clientWidth);
  expect(width).toBe(Math.round((1224 / 1.5) * zoomed / 100));
  await page.getByRole('button', { name: 'Zoom out' }).click();
  await expect(value).not.toHaveText(`${zoomed}%`);
  await page.getByRole('button', { name: 'Fit' }).click();
  await expect(value).toHaveText(`${fit}%`);
});
