import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U011: Admin › Audit trail (UI spec finding 29). Sentence rows, one toolbar, More filters, rows that open to
 * every recorded field, one Export menu. Same harness as adminPlatform.spec.ts: the real app on the Vite dev server
 * (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c8c8c8c8-0000-4000-8000-00000000000e', name: 'Ops Entity', slug: 'ops-entity' };
const CAPS = capabilitiesFor('TENANT_IT_ADMIN');

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'it-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
const minsAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();

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
      back.searchParams.set('code', 'it-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'it-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const SYSTEM = '00000000-0000-0000-0000-000000000000';
const DANA = 'a1b2c3d4-0000-4000-8000-000000000001';
const SAM = 'b2c3d4e5-0000-4000-8000-000000000002';
const PKG = '3f2a91c4-1111-4222-8333-444455556666';
const REF2 = 'c0ffee00-0000-4000-8000-000000000002';
const rec = (n: number, m: number, action: string, actorId: string, resourceType: string, resourceId: string, outcome: string, detail: string) =>
  ({ id: `r-${n}`, actorId, action, resourceType, resourceId, outcome, correlationId: `c0ffee00-0000-4000-8000-00000000000${n}`, detail, occurredAt: minsAgo(m) });
const RECORDS = [
  rec(1, 5, 'SENSITIVE_DATA_POLICY_CHANGED', DANA, 'SensitiveDataPolicy', ENTITY.tenantId, 'SUCCESS', 'Sensitive-data policy changed: enabledCount=4, threshold=0.85, reviewMode=FOCUSED, maskSsnLastFour=true'),
  rec(2, 40, 'PACKAGE_FINAL_APPROVED', SAM, 'DocumentPackage', PKG, 'SUCCESS', 'Final delivery approved'),
  rec(3, 80, 'FINDING_CONFIRMED', SYSTEM, 'Finding', 'f-1', 'SUCCESS', 'Finding confirmed: type=SSN'),
  rec(4, 120, 'FINDING_DISMISSED', DANA, 'Finding', 'f-2', 'SUCCESS', 'Finding dismissed: type=CREDIT_SCORE'),
  rec(5, 200, 'ENTERPRISE_IDENTITY_CONNECTION_TESTED', DANA, 'EnterpriseIdentityConnection', 'eic-1', 'DENIED', 'Test older than 15 minutes'),
];

const pageErrors: string[] = [];
test.beforeEach(({ page }) => { pageErrors.length = 0; page.on('pageerror', (e) => pageErrors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); }); });
test.afterEach(() => { expect(pageErrors.filter((e) => !/Failed to load resource|GET \/packages returned/.test(e))).toEqual([]); });

async function openAudit(page: Page, queries: URLSearchParams[], records: Array<Record<string, unknown>> = RECORDS) {
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: ['TENANT_IT_ADMIN'] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'it.user@example.test', roles: ['TENANT_IT_ADMIN'], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: CAPS });
    }
    if (url.pathname === '/api/v1/integrations' || url.pathname === '/api/v1/derived-deliveries/attention') return json([]);
    if (url.pathname === '/api/v1/audit-records') {
      queries.push(url.searchParams);
      const q = url.searchParams;
      return json(records.filter((r) => (!q.get('actorId') || r.actorId === q.get('actorId'))
        && (!q.get('resourceId') || r.resourceId === q.get('resourceId'))
        && (!q.get('action') || r.action === q.get('action'))));
    }
    if (url.pathname === '/api/v1/audit-records/export') {
      queries.push(url.searchParams);
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Audit trail/ }).click();
  await expect(page.getByRole('region', { name: 'Audit records' }).getByRole('table')).toBeVisible();
}

const region = (page: Page) => page.getByRole('region', { name: 'Audit records' });
const row = (page: Page, text: string) => region(page).getByRole('row').filter({ hasText: text });

test('rows read as sentences with who, case and result; codes only in an opened row', async ({ page }) => {
  const queries: URLSearchParams[] = [];
  await openAudit(page, queries);
  for (const h of ['When', 'What happened', 'Who', 'Case', 'Result']) await expect(region(page).getByRole('columnheader', { name: h })).toBeVisible();
  await expect(row(page, 'Changed the sensitive-data settings')).toContainText('auto-redact level now 85%');
  await expect(row(page, 'Changed the sensitive-data settings')).toContainText('Person 00000001');
  await expect(row(page, 'Approved delivery')).toContainText('55556666');
  await expect(row(page, 'Confirmed a Social Security Number')).toContainText('LexaVault (automatic)');
  await expect(row(page, 'Kept a credit score visible')).toContainText('Success');
  await expect(row(page, 'Tested the sign-in setup')).toContainText('Refused');
  const text = await region(page).evaluate((e) => { const c = e.cloneNode(true) as HTMLElement; c.querySelectorAll('details, option').forEach((d) => d.remove()); return c.innerText; });
  expect(text).not.toMatch(/[A-Z]{3,}_[A-Z]/);
  // Default: everything, the last 7 days.
  expect(queries[0].get('category')).toBe('MEANINGFUL');
  const from = Date.parse(queries[0].get('from') || '');
  expect(Math.abs(Date.now() - 7 * 864e5 - from)).toBeLessThan(5 * 60e3);

  // A row opens to every recorded field, unchanged.
  const what = region(page).getByRole('button', { name: 'Confirmed a Social Security Number' });
  await what.click();
  await expect(what).toHaveAttribute('aria-expanded', 'true');
  const detail = region(page).locator('.cf-ad-audit-detail');
  for (const v of ['FINDING_CONFIRMED', 'Finding confirmed: type=SSN', SYSTEM, 'Finding', 'f-1', 'r-3', 'c0ffee00-0000-4000-8000-000000000003']) await expect(detail).toContainText(v);
});

test('toolbar: type, person, period and case search drive the server filters', async ({ page }) => {
  const queries: URLSearchParams[] = [];
  await openAudit(page, queries);
  const types = page.getByRole('radiogroup', { name: 'Type' });
  for (const t of ['Everything', 'Sign-in', 'Settings', 'Cases', 'Exports', 'Refused']) await expect(types.getByRole('radio', { name: t })).toBeVisible();
  await types.getByRole('radio', { name: 'Refused' }).click();
  await expect.poll(() => queries.at(-1)?.get('category')).toBe('FAILURES_DENIED');
  await types.getByRole('radio', { name: 'Everything' }).click();

  const person = page.getByRole('combobox', { name: 'Person' });
  await expect(person.locator('option')).toHaveText(['Person: anyone', 'LexaVault (automatic)', 'Person 00000001', 'Person 00000002']);
  await person.selectOption({ label: 'LexaVault (automatic)' });
  await expect.poll(() => queries.at(-1)?.get('actorId')).toBe(SYSTEM);
  await expect(region(page).getByRole('row')).toHaveCount(2);
  await person.selectOption({ label: 'Person: anyone' });

  await page.getByRole('combobox', { name: 'Period' }).selectOption({ label: 'Any time' });
  await expect.poll(() => queries.at(-1)?.has('from')).toBe(false);

  const search = page.getByPlaceholder('Loan number or case ID');
  await search.fill('LN204877');
  await search.press('Enter');
  await expect(page.getByRole('alert')).toContainText('does not record loan numbers yet');
  await search.fill(PKG);
  await search.press('Enter');
  await expect.poll(() => queries.at(-1)?.get('resourceId')).toBe(PKG);
  await expect(region(page).getByRole('row')).toHaveCount(2);
  await expect(row(page, 'Approved delivery')).toBeVisible();
});

test('More filters hold the exact ones; Export is one menu with CSV and JSON', async ({ page }) => {
  const queries: URLSearchParams[] = [];
  await openAudit(page, queries);
  const more = page.locator('details.cf-ad-audit-more');
  expect(await more.getAttribute('open')).toBeNull();
  await more.getByText('More filters').click();
  for (const l of ['Action', 'Result', 'Item type', 'Item ID', 'Person ID', 'Reference', 'From', 'To']) await expect(more.getByLabel(new RegExp(`^${l}`))).toBeVisible();
  await more.getByLabel(/^Action/).selectOption({ label: 'Approved delivery' });
  await more.getByLabel(/^Reference/).fill(REF2);
  await more.getByRole('button', { name: 'Apply filters' }).click();
  await expect.poll(() => queries.at(-1)?.get('action')).toBe('PACKAGE_FINAL_APPROVED');
  expect(queries.at(-1)?.get('correlationId')).toBe(REF2);
  await expect(more.getByText('More filters (2)')).toBeVisible();

  await page.getByRole('button', { name: 'Export', exact: true }).click();
  const menu = page.getByRole('menu', { name: 'Export' });
  await expect(menu.getByRole('menuitem')).toHaveCount(2);
  // Capture the file link instead of a real download: a real one in Edge runs SmartScreen and can close the browser
  // under the next test.
  await page.evaluate(() => {
    HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement) { (window as unknown as { saved: string }).saved = this.download; };
  });
  await menu.getByRole('menuitem', { name: /JSON/ }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { saved?: string }).saved)).toBe('lexavault-audit.json');
  const exp = queries.at(-1)!;
  expect(exp.get('format')).toBe('json');
  expect(exp.get('action')).toBe('PACKAGE_FINAL_APPROVED');
  await expect(menu).toHaveCount(0);
});

test('0019 A2: Who shows the recorded name; the system and older records keep their labels; the Person filter sends the id', async ({ page }) => {
  const named = [
    { ...RECORDS[0], actorDisplayName: 'Dana Reviewer' },          // new record: name recorded
    { ...RECORDS[1], actorDisplayName: null },                     // older record: no name
    { ...RECORDS[2], actorDisplayName: null },                     // system work
    { ...RECORDS[3], actorDisplayName: 'Dana Reviewer' },
  ];
  const queries: URLSearchParams[] = [];
  await openAudit(page, queries, named);
  await expect(row(page, 'Changed the sensitive-data settings').getByRole('cell').nth(2)).toHaveText('Dana Reviewer');
  await expect(row(page, 'Approved delivery').getByRole('cell').nth(2)).toHaveText('Person 00000002');
  await expect(row(page, 'Confirmed a Social Security Number').getByRole('cell').nth(2)).toHaveText('LexaVault (automatic)');

  // The opened record shows the recorded name next to the id.
  await region(page).getByRole('button', { name: 'Changed the sensitive-data settings' }).click();
  const detail = region(page).locator('.cf-ad-audit-detail');
  await expect(detail.locator('div').filter({ has: page.locator('dt', { hasText: /^Person$/ }) }).locator('dd')).toHaveText('Dana Reviewer');
  await expect(detail.locator('div').filter({ has: page.locator('dt', { hasText: /^Person ID$/ }) }).locator('dd')).toHaveText(DANA);
  await region(page).getByRole('button', { name: 'Changed the sensitive-data settings' }).click();
  await region(page).getByRole('button', { name: 'Approved delivery' }).click();
  await expect(region(page).locator('.cf-ad-audit-detail dt', { hasText: /^Person$/ })).toHaveCount(0);

  const person = page.getByRole('combobox', { name: 'Person' });
  await expect(person.locator('option')).toHaveText(['Person: anyone', 'LexaVault (automatic)', 'Dana Reviewer', 'Person 00000002']);
  await person.selectOption({ label: 'Dana Reviewer' });
  await expect.poll(() => queries.at(-1)?.get('actorId')).toBe(DANA);
  await expect(person).toHaveValue(DANA);
});
