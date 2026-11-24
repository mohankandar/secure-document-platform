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
  configurationVersion: 4, createdAt: minsAgo(90000), updatedAt: minsAgo(9000), lastTestedAt: minsAgo(9100), lastTestStatus: 'PASSED', lastTestMessage: 'Discovery OK',
};
const STAGED = { ...ACTIVE, connectionId: 'idc-2', status: 'VALIDATED', configurationVersion: 5, issuerUri: 'https://login2.northwind.example/v2.0' };

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
  const card = page.getByRole('region', { name: 'Login hub' });
  await expect(card).toBeVisible();
  return card;
}

test('IT Administrator: a read-only card with the login system\'s name and address, and no controls', async ({ page }) => {
  const writes: string[] = [];
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, overview: { active: ACTIVE, staged: STAGED, rollbackCandidates: [{ ...ACTIVE, configurationVersion: 3, status: 'DISABLED' }], activationSupported: true, activationMaxTestAgeSeconds: 900 } }, writes);
  const card = await openSignInTab(page);

  await expect(card).toContainText('Your sign-in is managed through the login hub. To change it, contact LexaVault support.');
  // Task 0041: the server side is read-only too; sign-in connections are set through setup.
  await expect(card).toContainText('Sign-in connections are set through setup.');
  const facts = card.getByRole('list', { name: 'Login system' }).or(card.locator('dl'));
  await expect(facts).toContainText('Login system');
  await expect(facts).toContainText('Northwind Entra ID');
  await expect(facts).toContainText('Address');
  await expect(facts).toContainText('https://login.northwind.example/tenant/v2.0');

  const main = page.locator('.cf-admin');
  await expect(main.locator('input, textarea, select')).toHaveCount(0);
  // U026: the "?" help; task 0054: the read-only test. Neither changes anything.
  await expect(card.getByRole('button')).toHaveCount(2);
  await expect(card.getByRole('button', { name: 'About Sign-in', exact: true })).toBeVisible();
  await expect(card.getByRole('button', { name: 'Test sign-in', exact: true })).toBeVisible();
  for (const name of [/save/i, /activate/i, /switch on/i, /roll back/i, /edit/i]) {
    await expect(main.getByRole('button', { name })).toHaveCount(0);
  }
  const text = await main.innerText();
  for (const old of [/Staged/, /draft/i, /Issuer/, /API audience/, /Browser client/, /Known-good rollback/, /login2\.northwind/, /VALIDATED|ACTIVE/]) {
    expect(text).not.toMatch(old);
  }
  await expect(page.getByText('How people sign in. It is managed through the login hub.')).toBeVisible();
  expect(writes).toEqual([]);
});

test('IT Administrator: Test sign-in says it passed, or gives the exact code and its words; nothing else is written', async ({ page }) => {
  const writes: string[] = [];
  const answers = [
    { body: { passed: true, code: null, message: 'Its key set holds 2 signing key(s). No token was checked.', reason: null, testedAt: new Date().toISOString() } },
    { body: { passed: false, code: 'IDN_TEST_ISSUER_UNREACHABLE', message: "The issuer's discovery document could not be fetched (OUT_TLS_FAILED).", reason: 'OUT_TLS_FAILED', testedAt: new Date().toISOString() } },
    { status: 403, body: { errorCode: 'ACCESS_DENIED', code: 'LV-AUT-006', message: 'Not allowed' } },
  ];
  let at = 0;
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, test: () => answers[at++], overview: { active: ACTIVE, staged: null, rollbackCandidates: [], activationSupported: false, activationMaxTestAgeSeconds: 0 } }, writes);
  const card = await openSignInTab(page);
  const button = card.getByRole('button', { name: 'Test sign-in' });
  const answer = card.getByRole('status');

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
  await signIn(page, { roles: ['TENANT_IT_ADMIN'], caps: IT_CAPS, overview: { active: null, staged: null, rollbackCandidates: [], activationSupported: false, activationMaxTestAgeSeconds: 900 } }, []);
  const card = await openSignInTab(page);
  await expect(card).toContainText('Your sign-in is managed through the login hub. To change it, contact LexaVault support.');
  await expect(card.locator('dl')).toHaveCount(0);
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
  await expect(page.getByRole('region', { name: 'Login hub' })).toHaveCount(0);
  await expect(page.getByText('Your sign-in is managed through the login hub.')).toHaveCount(0);
});
