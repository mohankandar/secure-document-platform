import { test, expect, type ConsoleMessage, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * Task 0023 B6 (inventory #277, #278): the identity provider's token-endpoint error body must never reach the browser
 * console, in any build. This suite runs on the Vite dev server (a development build), so it also proves the
 * development-only debug lines carry no body. Only the OAuth error code (such as "invalid_grant") may appear.
 * The identity provider and the API are mocked; the body holds a marker that must not be seen anywhere.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'a1a1a1a1-0000-4000-8000-00000000000a', name: 'Console Entity', slug: 'console-entity', roles: ['DOCUMENT_SUPERVISOR'] };
const MARKER = 'IDP-BODY-MARKER-7f3a';
const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };

function fakeJwt(lifetimeSeconds: number): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'console-user', exp: Math.floor(Date.now() / 1000) + lifetimeSeconds })}.signature`;
}

type TokenAnswer = { status: number; body: string };

async function mockIdentityProvider(page: Page, answers: { exchange: TokenAnswer; refresh?: TokenAnswer }) {
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
      back.searchParams.set('code', 'console-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      const refresh = (request.postData() || '').includes('grant_type=refresh_token');
      const answer = refresh && answers.refresh ? answers.refresh : answers.exchange;
      return route.fulfill({ status: answer.status, headers: cors, contentType: 'application/json', body: answer.body });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

async function mockApi(page: Page) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'console.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC',
        capabilities: capabilitiesFor(...ENTITY.roles) });
    }
    if (url.pathname.startsWith('/api/v1/review-items')) return json({ items: [], totalItems: 0 });
    if (url.pathname.startsWith('/api/v1/packages')) return json([]);
    return json({});
  });
}

/** Every console line, with the text of each argument (objects included), so a body inside a details object is seen. */
function recordConsole(page: Page): string[] {
  const lines: string[] = [];
  page.on('console', (message: ConsoleMessage) => {
    void Promise.all(message.args().map((arg) => arg.evaluate((value) => {
      if (value instanceof Error) return `${value.name}: ${value.message}`;
      try { return typeof value === 'string' ? value : JSON.stringify(value); } catch { return String(value); }
    }).catch(() => ''))).then((args) => lines.push([message.text(), ...args].join(' ')));
  });
  page.on('pageerror', (error) => lines.push(`${error.name}: ${error.message}`));
  return lines;
}

const errorBody = JSON.stringify({ error: 'invalid_grant', error_description: `Code not valid for ${MARKER}`, client: MARKER });

test('a refused code exchange shows the safe message; the console has the OAuth code only, never the IdP body', async ({ page }) => {
  const lines = recordConsole(page);
  await mockIdentityProvider(page, { exchange: { status: 400, body: errorBody } });
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText('Sign-in could not be completed. Please try again.');
  await expect.poll(() => lines.some((line) => line.includes('OIDC_TOKEN_EXCHANGE_FAILED HTTP 400'))).toBe(true);
  expect(lines.filter((line) => line.includes('OIDC_TOKEN_EXCHANGE_FAILED')).every((line) => line.includes('idpError=invalid_grant'))).toBe(true);
  expect(lines.filter((line) => line.includes(MARKER))).toEqual([]);
  expect(lines.filter((line) => line.includes('error_description'))).toEqual([]);
});

test('a code exchange refused with a body that is not JSON reads idpError=unknown, and the body is not shown', async ({ page }) => {
  const lines = recordConsole(page);
  await mockIdentityProvider(page, { exchange: { status: 502, body: `<html>${MARKER}</html>` } });
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText('Sign-in could not be completed. Please try again.');
  await expect.poll(() => lines.some((line) => line.includes('OIDC_TOKEN_EXCHANGE_FAILED HTTP 502') && line.includes('idpError=unknown'))).toBe(true);
  expect(lines.filter((line) => line.includes(MARKER))).toEqual([]);
});

for (const status of [400, 500]) {
  test(`a refused token refresh (HTTP ${status}) never puts the IdP body in the console`, async ({ page }) => {
    const lines = recordConsole(page);
    // The first access token is within the refresh window, so the first API call refreshes it.
    await mockIdentityProvider(page, {
      exchange: { status: 200, body: JSON.stringify({ access_token: fakeJwt(30), id_token: fakeJwt(3600), refresh_token: 'console-refresh', token_type: 'Bearer', expires_in: 30 }) },
      refresh: { status, body: errorBody },
    });
    await mockApi(page);
    const refreshed = page.waitForRequest((request) => request.url().endsWith('/protocol/openid-connect/token') && (request.postData() || '').includes('grant_type=refresh_token'));
    await page.goto('/');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await refreshed;
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    // Give the console a moment to receive the failure lines.
    await page.waitForTimeout(500);
    expect(lines.filter((line) => line.includes(MARKER))).toEqual([]);
    expect(lines.filter((line) => line.includes('error_description'))).toEqual([]);
  });
}
