import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U009: Admin › Sign-in (UI spec finding 15), owner's decision B. Sign-in is managed through the login hub,
 * so the tab is a read-only card for the IT Administrator: the words, and the login system's name and address as plain
 * text. No edit, switch-on or roll-back control. Other roles don't see the tab. Task 0054: one read-only "Test sign-in".
 *
 * Same harness as adminIntegrations.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c8c8c8c8-0000-4000-8000-00000000000e', name: 'Sign-in Entity', slug: 'signin-entity' };
const IT_CAPS = capabilitiesFor('TENANT_IT_ADMIN');
const BUSINESS_CAPS = capabilitiesFor('BUSINESS_ADMIN');


const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
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

const ACTIVE = {
  connectionId: 'idc-1', tenantId: ENTITY.tenantId, providerId: 'customer-oidc', providerType: 'OIDC', displayName: 'Northwind Entra ID',
  issuerUri: 'https://login.northwind.example/tenant/v2.0', browserClientId: 'lexavault-web', apiAudience: 'lexavault-api', status: 'ACTIVE',
  configurationVersion: 4, matchesSettings: true,
};

const pageErrors: string[] = [];
test.beforeEach(({ page }) => { pageErrors.length = 0; page.on('pageerror', (e) => pageErrors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); }); });
// Other screens get thin mock answers; only errors from the Sign-in tab count here.
test.afterEach(() => { expect(pageErrors.filter((e) => !/Failed to load resource|GET \/(packages|operations\/reports|business-admin\/configuration|platform-admin\/overview) returned/.test(e))).toEqual([]); });

type Who = { roles: string[]; caps: string[]; overview?: object; test?: () => { status?: number; body: object } };

async function signIn(page: Page, who: Who, writes: string[]) {
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (request.method() !== 'GET') writes.push(`${request.method()} ${url.pathname}`);
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: who.roles }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'someone@example.test', roles: who.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: who.caps });
    }
    if (url.pathname === '/api/v1/admin/enterprise-identity') {
      return who.overview ? json(who.overview) : json({ code: 'NOT_FOUND', message: 'none' }, 404);
    }
    if (url.pathname === '/api/v1/admin/enterprise-identity/test-sign-in' && request.method() === 'POST' && who.test) {
      expect(request.postData()).toBeNull(); // no token, no address: nothing is sent
      const answer = who.test();
      return json(answer.body, answer.status ?? 200);
    }
    // The Admin screen opens its first area (Integrations) before the Sign-in tab is chosen.
    if (url.pathname === '/api/v1/integrations' || url.pathname === '/api/v1/derived-deliveries/attention') return json([]);
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('navigation', { name: 'Workspace' })).toBeVisible();
}

async function openSignInTab(page: Page) {
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Sign-in/ }).click();
  const card = page.getByRole('region', { name: 'Sign-in in use' });
  await expect(card).toBeVisible();
  return card;
}

test('IT Administrator: the login system in use, and a change only when one is started', async ({ page }) => {
  const writes: string[] = [];
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, overview: { active: ACTIVE, draft: null, previous: null, history: [] } }, writes);
  const card = await openSignInTab(page);
  const facts = card.locator('dl');
  await expect(facts).toContainText('Login system');
  await expect(facts).toContainText('Northwind Entra ID');
  await expect(facts).toContainText('Address');
  await expect(facts).toContainText('https://login.northwind.example/tenant/v2.0');
  await expect(card.getByRole('button')).toHaveCount(2);
  await expect(card.getByRole('button', { name: 'About Sign-in', exact: true })).toBeVisible();
  await expect(card.getByRole('button', { name: 'Test sign-in', exact: true })).toBeVisible();

  // Task 0058: without a draft there is nothing to type, switch to or go back to (it was read-only since 0041).
  const change = page.getByRole('region', { name: 'Change the sign-in' });
  await expect(page.locator('.cf-admin').locator('input, textarea, select')).toHaveCount(0);
  await expect(change.getByRole('button', { name: 'Prepare a change' })).toBeVisible();
  for (const name of [/switch/i, /go back/i, /save/i, /discard/i]) await expect(change.getByRole('button', { name })).toHaveCount(0);
  expect(writes).toEqual([]);
});

const DRAFT = { ...ACTIVE, connectionId: 'idc-2', status: 'DRAFT', configurationVersion: 1, matchesSettings: true };
const DRAFT_URL = 'POST /api/v1/admin/enterprise-identity';
type Step = { call: string; body: Record<string, unknown> };

/** The sign-in change's server: each step's answer, and the overview after it. */
async function changeServer(page: Page, steps: Record<string, { answer?: object; then?: object }>) {
  const seen: Step[] = [];
  let overview: object = { active: ACTIVE, draft: null, previous: null, history: [] };
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, overview }, []);
  await page.route('**/api/v1/admin/enterprise-identity**', async (route) => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(overview) });
    const call = `${request.method()} ${new URL(request.url()).pathname}`;
    seen.push({ call, body: request.postDataJSON() });
    const step = steps[call] ?? {};
    if (step.then) overview = step.then;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(step.answer ?? { done: true, code: 'OK', message: '', whatToDo: null, reason: null }) });
  });
  await openSignInTab(page);
  return { seen, change: page.getByRole('region', { name: 'Change the sign-in' }) };
}

test('a change is prepared, saved, tested, switched to and gone back from, each step at the version seen', async ({ page }) => {
  const next = { ...ACTIVE, connectionId: 'idc-2', configurationVersion: 5, browserClientId: 'lexavault-web-2' };
  const { seen, change } = await changeServer(page, {
    [`${DRAFT_URL}/draft`]: { then: { active: ACTIVE, draft: DRAFT, previous: null, history: [] } },
    'PUT /api/v1/admin/enterprise-identity/draft': { then: { active: ACTIVE, draft: { ...DRAFT, configurationVersion: 2, browserClientId: 'lexavault-web-2' }, previous: null, history: [] } },
    [`${DRAFT_URL}/draft/test-sign-in`]: { then: { active: ACTIVE, draft: { ...DRAFT, status: 'VALIDATED', configurationVersion: 3, browserClientId: 'lexavault-web-2' }, previous: null, history: [] } },
    [`${DRAFT_URL}/draft/activate`]: { then: { active: next, draft: null, previous: ACTIVE, history: [] } },
    [`${DRAFT_URL}/rollback`]: { then: { active: ACTIVE, draft: null, previous: null, history: [] } },
  });
  await change.getByRole('button', { name: 'Prepare a change' }).click();
  await expect(change.getByRole('button', { name: 'Switch to it' })).toBeDisabled();
  await change.getByLabel('Web client ID').fill('lexavault-web-2');
  await expect(change.getByRole('button', { name: 'Test the draft' })).toBeDisabled(); // save first
  await change.getByRole('button', { name: 'Save draft' }).click();
  await expect(change.getByRole('button', { name: 'Test the draft' })).toBeEnabled();
  await change.getByLabel('Test token').fill('header.payload.signature');
  await change.getByRole('button', { name: 'Test the draft' }).click();
  await expect(change.getByText('Draft, tested')).toBeVisible();
  await expect(change.getByLabel('Test token')).toHaveValue('');
  await change.getByRole('button', { name: 'Switch to it' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Switch', exact: true }).click();
  await change.getByRole('button', { name: 'Go back to the previous sign-in' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Go back', exact: true }).click();
  await expect(change.getByRole('button', { name: /go back/i })).toHaveCount(0);
  expect(seen.map((s) => [s.call.replace('/api/v1/admin/enterprise-identity', ''), s.body.expectedVersion])).toEqual([
    ['POST /draft', 4], ['PUT /draft', 1], ['POST /draft/test-sign-in', 2], ['POST /draft/activate', 3], ['POST /rollback', 5]]);
  expect(seen[2].body.token).toBe('header.payload.signature');
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }))).not.toContain('header.payload.signature');
});

test('a failed test says what failed and what to do; a draft of another login system can be tested but not switched to', async ({ page }) => {
  const other = { ...DRAFT, status: 'VALIDATED', issuerUri: 'https://login2.northwind.example', matchesSettings: false };
  const { change } = await changeServer(page, {
    [`${DRAFT_URL}/draft`]: { then: { active: ACTIVE, draft: other, previous: null, history: [{ issuerUri: other.issuerUri, version: 1, status: 'DRAFT', source: 'APP', changedBy: 'x', changedAt: minsAgo(1) }] } },
    [`${DRAFT_URL}/draft/test-sign-in`]: { answer: { done: false, code: 'IDN_TEST_AUDIENCE', message: 'The token is not for this audience.', whatToDo: 'Set the API audience.', reason: null } },
  });
  await change.getByRole('button', { name: 'Prepare a change' }).click();
  await expect(change.getByText('it can be tested here but not switched to')).toBeVisible();
  await expect(change.getByRole('button', { name: 'Switch to it' })).toBeDisabled();
  await change.getByRole('button', { name: 'Test the draft' }).click();
  await expect(change).toContainText('IDN_TEST_AUDIENCE');
  await expect(change).toContainText('What to do: Set the API audience.');
  await page.getByText('Changes so far').click();
  await expect(page.getByRole('table', { name: /Changes of the sign-in/ })).toContainText('An IT Admin');
});

test('IT Administrator: Test sign-in says it passed, or gives the exact code and its words; nothing else is written', async ({ page }) => {
  const writes: string[] = [];
  const answers = [
    { body: { passed: true, code: null, message: 'Its key set holds 2 signing key(s). No token was checked.', reason: null, testedAt: new Date().toISOString() } },
    { body: { passed: false, code: 'IDN_TEST_ISSUER_UNREACHABLE', message: "The issuer's discovery document could not be fetched (OUT_TLS_FAILED).", reason: 'OUT_TLS_FAILED', testedAt: new Date().toISOString() } },
    { status: 403, body: { errorCode: 'ACCESS_DENIED', code: 'LV-AUT-006', message: 'Not allowed' } },
  ];
  let at = 0;
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, test: () => answers[at++], overview: { active: ACTIVE, draft: null, previous: null, history: [] } }, writes);
  const card = await openSignInTab(page);
  const button = card.getByRole('button', { name: 'Test sign-in' });
  // Not the button's spinner, which is a status too while the test runs.
  const answer = card.locator('div[role="status"]');

  await button.click();
  await expect(answer).toContainText('The login system answers.');
  await expect(answer).toContainText('Its key set holds 2 signing key(s). No token was checked.');
  await expect(answer).toContainText('Tested Today');

  await button.click();
  await expect(answer).toContainText('Sign-in test failed: IDN_TEST_ISSUER_UNREACHABLE');
  await expect(answer).toContainText("The issuer's discovery document could not be fetched (OUT_TLS_FAILED).");
  await expect(answer).not.toContainText('The login system answers.');

  await button.click();
  await expect(answer).toContainText('The test could not be run.');
  await expect(button).toBeEnabled();
  await expect(page.locator('.cf-admin').locator('input, textarea, select')).toHaveCount(0);
  expect(writes).toEqual(Array(3).fill('POST /api/v1/admin/enterprise-identity/test-sign-in'));
});

test('IT Administrator with no login system details: the card alone', async ({ page }) => {
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, overview: { active: null, draft: null, previous: null, history: [] } }, []);
  const card = await openSignInTab(page);
  await expect(card).toContainText('No sign-in connection is stored.');
  await expect(card.locator('dl')).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Change the sign-in' })).toHaveCount(0);
  // U026: the card's only button is its "?" help.
  await expect(card.getByRole('button')).toHaveCount(1);
  await expect(card.getByRole('button', { name: 'About Sign-in', exact: true })).toBeVisible();
});

test('Business Administrator: no Sign-in tab, and its address leads elsewhere', async ({ page }) => {
  await signIn(page, { roles: ['BUSINESS_ADMIN'], caps: BUSINESS_CAPS }, []);
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  const areas = page.getByRole('navigation', { name: 'Administration areas' });
  await expect(areas.getByRole('link', { name: /Redaction/ })).toBeVisible();
  await expect(areas.getByRole('link', { name: /Sign-in/ })).toHaveCount(0);
  await page.goto('/admin/identity');
  await expect(page.getByRole('region', { name: 'Sign-in in use' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Change the sign-in' })).toHaveCount(0);
});
