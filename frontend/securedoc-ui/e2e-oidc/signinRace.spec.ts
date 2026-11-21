import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * Regression tests for the single-entity sign-in race (report 0007, task 0008).
 *
 * The real app runs on the Vite dev server, so it is mounted under React.StrictMode and the
 * sign-in check in App.tsx runs twice. The identity provider (idp.test) and the API are mocked.
 * Before the fix, a user with exactly one entity got "Sign-in could not be completed" because
 * the discarded StrictMode run cleared the session.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY_A = { tenantId: 'a1a1a1a1-0000-4000-8000-00000000000a', name: 'Race Entity A', slug: 'race-entity-a', roles: ['DOCUMENT_SUPERVISOR'] };
const ENTITY_B = { tenantId: 'b2b2b2b2-0000-4000-8000-00000000000b', name: 'Race Entity B', slug: 'race-entity-b', roles: ['DOCUMENT_SUPERVISOR'] };

function fakeJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  // Unsigned test token: the browser reads exp, and the email only for the no-access screen (U013); the mocked API
  // does not verify it.
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'signin-race-user', email: 'race.user@example.test', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
}

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };

async function mockIdentityProvider(page: Page) {
  await page.route(`${ISSUER}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        issuer: ISSUER,
        authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
        token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
        end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
      }) });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/auth')) {
      // Simulate a successful login: send the browser back with a code and the app's own state.
      const back = new URL(url.searchParams.get('redirect_uri') || '/');
      back.searchParams.set('code', 'signin-race-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'signin-race-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

type ApiLog = { meSelections: Array<string | undefined> };

async function mockApi(page: Page, entities: typeof ENTITY_A[]): Promise<ApiLog> {
  const log: ApiLog = { meSelections: [] };
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const selected = route.request().headers()['x-selected-tenant-id'];
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json(entities);
    if (url.pathname === '/api/v1/me') {
      log.meSelections.push(selected);
      const entity = entities.find((candidate) => candidate.tenantId === selected) ?? (entities.length === 1 ? entities[0] : undefined);
      if (!entity) return json({ code: 'SECURITY_CONTEXT_INVALID' }, 403);
      return json({ username: 'signin.race', roles: entity.roles, tenantName: entity.name, tenantSlug: entity.slug, authenticationMode: 'OIDC',
        capabilities: capabilitiesFor(...entity.roles) });
    }
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    if (url.pathname.startsWith('/api/v1/review-items')) return json({ items: [], totalItems: 0 });
    if (url.pathname.startsWith('/api/v1/packages')) return json([]);
    return json({});
  });
  return log;
}

async function signIn(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
}

test('single-entity user opens the workspace on that entity under StrictMode', async ({ page }) => {
  await mockIdentityProvider(page);
  const api = await mockApi(page, [ENTITY_A]);

  await signIn(page);

  await expect(page.getByText('Race Entity A').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /account menu/i })).toBeVisible();
  await expect(page.getByText('Sign-in could not be completed')).toHaveCount(0);
  // The workspace was verified for the one authorized entity.
  expect(api.meSelections).toContain(ENTITY_A.tenantId);
  expect(await page.evaluate(() => sessionStorage.getItem('lexavault.oidc.selected-tenant'))).toBe(ENTITY_A.tenantId);
});

test('two-entity user still gets the chooser', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [ENTITY_A, ENTITY_B]);

  await signIn(page);

  await expect(page.getByRole('heading', { name: 'Choose an entity' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Race Entity A' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Race Entity B' })).toBeVisible();
  await page.getByRole('button', { name: 'Race Entity B' }).click();
  await expect(page.getByText('Race Entity B').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /account menu/i })).toBeVisible();
});

test('no-entity user still gets the refusal', async ({ page }) => {
  await mockIdentityProvider(page);
  const api = await mockApi(page, []);

  await signIn(page);

  // UI task U013 (owner's decision): no organization is a refusal like a 403 from /me. It shows the Case File
  // no-access screen and keeps the session, so Sign out can end it at the identity provider. Before U013 this
  // test expected the generic "Sign-in could not be completed" message and a cleared access token.
  // Task 0025: the title and the code only; no organization gives no code, so LV-AUT-002.
  await expect(page.getByRole('heading', { name: 'No access to LexaVault · LV-AUT-002' })).toBeVisible();
  // Task 0026: "Signed in as <email>" under the title and code.
  await expect(page.getByTestId('no-access-who')).toHaveText('Signed in as race.user@example.test');
  await expect(page.getByText('Sign-in could not be completed')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /account menu/i })).toHaveCount(0);
  expect(api.meSelections).toHaveLength(0);
  expect(await page.evaluate(() => sessionStorage.getItem('securedoc.oidc.access-token'))).not.toBeNull();

  const logout = page.waitForRequest((r) => r.url().includes('/protocol/openid-connect/logout'));
  await page.getByRole('button', { name: 'Sign out' }).click();
  const url = new URL((await logout).url());
  expect(url.searchParams.get('id_token_hint')).toBeTruthy();
});

test('a refused /me shows the same no-access screen', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [ENTITY_A]);
  // Registered last, so it answers /me before the shared mock: the server refuses this account.
  await page.route('**/api/v1/me', (route) => route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ errorCode: 'NOT_AUTHORIZED_FOR_ENTITY', code: 'LV-AUT-003', message: 'No access to this entity' }) }));

  await signIn(page);

  // Task 0025: the code from the server's 403 body; the internal reason never reaches the screen.
  await expect(page.getByRole('heading', { name: 'No access to this entity · LV-AUT-003' })).toBeVisible();
  await expect(page.getByText('NOT_AUTHORIZED_FOR_ENTITY')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  await expect(page.getByRole('button', { name: /account menu/i })).toHaveCount(0);
});
