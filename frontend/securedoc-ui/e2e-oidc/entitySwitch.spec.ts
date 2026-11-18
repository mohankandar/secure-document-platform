import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * Entity switch in the newest (Case File) design (task 0009).
 *
 * Same harness as signinRace.spec.ts: the real app on the Vite dev server (React.StrictMode, OIDC mode,
 * newest design on), with the identity provider (idp.test) and the API mocked. The mocked API answers per
 * X-Selected-Tenant-Id, like the real server, so the tests can see whether data and roles really change.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const A = { tenantId: 'a1a1a1a1-0000-4000-8000-00000000000a', name: 'Race Entity A', slug: 'race-entity-a', roles: ['DOCUMENT_SUPERVISOR'] };
const B = { tenantId: 'b2b2b2b2-0000-4000-8000-00000000000b', name: 'Race Entity B', slug: 'race-entity-b', roles: ['REPORT_VIEWER'] };
// Roles and capabilities differ per entity on purpose: A can take in documents, B can only see reports.
const CAPABILITIES: Record<string, string[]> = {
  [A.tenantId]: ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'MANUAL_REVIEW'],
  [B.tenantId]: ['DASHBOARD_ACCESS', 'REPORT_EXECUTIVE_VIEW'],
};
const CASE: Record<string, string> = { [A.tenantId]: 'A-CASE-1001', [B.tenantId]: 'B-CASE-2002' };

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'switch-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'switch-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'switch-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

type Api = {
  refuse: Set<string>;                          // entities whose /me the server refuses
  holdPackagesFor: string | null;               // the next /packages request for this entity is held open
  releaseHeld: () => void;
  heldRequestSeen: Promise<void>;
  meCalls: Array<string | undefined>;
};

async function mockApi(page: Page, entities: Array<typeof A>): Promise<Api> {
  let releaseHeld: () => void = () => {};
  let markHeldSeen: () => void = () => {};
  const api: Api = {
    refuse: new Set(), holdPackagesFor: null, meCalls: [],
    releaseHeld: () => releaseHeld(),
    heldRequestSeen: new Promise<void>((resolve) => { markHeldSeen = resolve; }),
  };
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const selected = route.request().headers()['x-selected-tenant-id'];
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json(entities);
    const entity = entities.find((candidate) => candidate.tenantId === selected) ?? (entities.length === 1 ? entities[0] : undefined);
    if (url.pathname === '/api/v1/me') {
      api.meCalls.push(selected);
      if (!entity || api.refuse.has(entity.tenantId)) return json({ code: 'SECURITY_CONTEXT_INVALID', message: 'Security context invalid' }, 403);
      return json({ username: 'switch.user', roles: entity.roles, tenantName: entity.name, tenantSlug: entity.slug, authenticationMode: 'OIDC',
        capabilities: CAPABILITIES[entity.tenantId] });
    }
    if (!entity) return json({ code: 'SECURITY_CONTEXT_INVALID' }, 403);
    if (url.pathname === '/api/v1/packages') {
      if (api.holdPackagesFor === entity.tenantId) {
        api.holdPackagesFor = null;
        markHeldSeen();
        await new Promise<void>((resolve) => { releaseHeld = resolve; });
        // A late answer for the previous entity, marked so the test can prove it is never shown.
        return json([packageFor('A-LATE-9999', entity.tenantId)]);
      }
      return json([packageFor(CASE[entity.tenantId], entity.tenantId)]);
    }
    if (url.pathname.startsWith('/api/v1/review-items')) return json({ items: [], totalItems: 0 });
    if (url.pathname.startsWith('/api/v1/intake/identifier-definitions')) return json([]);
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  return api;
}

function packageFor(caseNumber: string, tenantId: string) {
  return {
    packageId: `${tenantId.slice(0, 8)}-0000-4000-8000-${caseNumber.replace(/\D/g, '').padStart(12, '0')}`,
    tenantId, packageType: 'MANUAL_UPLOAD', status: 'PENDING_REVIEW', documentCount: 1,
    createdAt: '2026-11-17T12:00:00Z', updatedAt: '2026-11-17T12:00:00Z',
    businessIdentifiers: [{ key: 'contract_number', label: 'Contract Number', value: caseNumber, primary: true, searchable: true, displayable: true, displayOrder: 1 }],
  };
}

async function signInAndChoose(page: Page, entityName?: string) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  if (entityName) await page.getByRole('button', { name: entityName }).click();
}

// Since UI task U002 the switch lives in the account menu: the account button names the current entity, and the
// entities are menu items in the "Entities" group, the current one checked.
const switchControl = (page: Page) => page.getByRole('button', { name: /account menu/i });
const accountMenu = (page: Page) => page.getByRole('menu', { name: 'Account' });
const entityItems = (page: Page) => accountMenu(page).getByRole('group', { name: 'Entities' }).getByRole('menuitemradio');
const workspaceNav = (page: Page) => page.getByRole('navigation', { name: 'Workspace' });

async function switchTo(page: Page, entityName: string) {
  await switchControl(page).click();
  await entityItems(page).filter({ hasText: entityName }).click();
}

/**
 * Samples the screen every 20 ms from the moment the switch takes effect until the new organization's data is
 * shown, and fails if the previous organization's data is visible at any moment in between. (Without the per-mount
 * data cache in CaseFileApp, the previous organization's cached case list briefly showed under the new name.)
 *
 * The switch takes effect when the server has accepted the new organization: until then the app keeps the previous
 * organization's screen on purpose (a refused choice changes nothing), with the account menu still open. Samples
 * taken while the menu is open are not judged. The menu is checked before the screen is read, so a reading taken
 * while it was open is never judged as after the switch. (UI task U001: sampling from the click made this test
 * fail whenever the server's answer was slower than the first sample, 5 runs in 10 before U001.)
 */
async function expectNoStaleDataDuringSwitch(page: Page, staleText: string, freshText: string) {
  const panel = accountMenu(page);
  for (let sample = 0; sample < 250; sample++) {
    const switching = (await panel.count()) > 0;
    const text = await page.locator('body').innerText().catch(() => '');
    if (!switching) expect(text, `previous organization's data (${staleText}) shown during the switch`).not.toContain(staleText);
    if (text.includes(freshText)) return;
    await page.waitForTimeout(20);
  }
  throw new Error(`${freshText} did not appear after the switch`);
}

test('two entities: switching from A to B shows only B data and B roles, and back again', async ({ page }) => {
  await mockIdentityProvider(page);
  const api = await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');

  await expect(page.getByText('A-CASE-1001')).toBeVisible();
  await expect(workspaceNav(page).getByRole('link', { name: 'New case' })).toBeVisible();
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity A/);

  // The list is read fresh from the server and marks the current entity.
  await switchControl(page).click();
  await expect(entityItems(page).filter({ hasText: 'Race Entity A' })).toHaveAttribute('aria-checked', 'true');
  await expect(entityItems(page).filter({ hasText: 'Race Entity B' })).toHaveAttribute('aria-checked', 'false');
  await entityItems(page).filter({ hasText: 'Race Entity B' }).click();
  await expectNoStaleDataDuringSwitch(page, 'A-CASE-1001', 'B-CASE-2002');

  await expect(page.getByText('B-CASE-2002')).toBeVisible();
  await expect(page.getByText('A-CASE-1001')).toHaveCount(0);
  await expect(workspaceNav(page).getByRole('link', { name: 'Reports' })).toBeVisible();
  await expect(workspaceNav(page).getByRole('link', { name: 'New case' })).toHaveCount(0);
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity B/);
  await expect(page).toHaveURL(/localhost:15175\/$/);
  expect(await page.evaluate(() => sessionStorage.getItem('lexavault.oidc.selected-tenant'))).toBe(B.tenantId);
  expect(api.meCalls).toContain(B.tenantId);

  await switchTo(page, 'Race Entity A');
  await expectNoStaleDataDuringSwitch(page, 'B-CASE-2002', 'A-CASE-1001');
  await expect(page.getByText('A-CASE-1001')).toBeVisible();
  await expect(page.getByText('B-CASE-2002')).toHaveCount(0);
  await expect(workspaceNav(page).getByRole('link', { name: 'New case' })).toBeVisible();
});

test('a request still in flight during the switch is discarded, not shown', async ({ page }) => {
  await mockIdentityProvider(page);
  const api = await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');
  await expect(page.getByText('A-CASE-1001')).toBeVisible();

  // Hold A's next case-list refresh open, switch to B while it is in flight, then let it answer.
  api.holdPackagesFor = A.tenantId;
  await api.heldRequestSeen;
  await switchTo(page, 'Race Entity B');
  await expect(page.getByText('B-CASE-2002')).toBeVisible();
  api.releaseHeld();

  await page.waitForTimeout(2500);
  await expect(page.getByText('A-LATE-9999')).toHaveCount(0);
  await expect(page.getByText('A-CASE-1001')).toHaveCount(0);
  await expect(page.getByText('B-CASE-2002')).toBeVisible();
});

test('one entity: no switch control, the name is plain text', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [A]);
  await signInAndChoose(page);

  await expect(page.getByText('A-CASE-1001')).toBeVisible();
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity A/);
  await switchControl(page).click();
  await expect(accountMenu(page)).toBeVisible();
  await expect(accountMenu(page).getByText('Race Entity A')).toBeVisible();
  await expect(accountMenu(page).getByRole('group', { name: 'Entities' })).toHaveCount(0);
  await expect(page.getByRole('menuitemradio', { name: /Race Entity/ })).toHaveCount(0);
});

test('the account menu has no test-role picker outside local security mode', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');
  await switchControl(page).click();
  await expect(accountMenu(page)).toBeVisible();
  await expect(accountMenu(page).getByRole('group', { name: 'Theme' })).toBeVisible();
  await expect(accountMenu(page).getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  await expect(accountMenu(page).getByRole('group', { name: 'Test role' })).toHaveCount(0);
  await expect(page.getByRole('menuitemradio', { name: 'Document Supervisor' })).toHaveCount(0);
  await expect(page.getByLabel('Local test role')).toHaveCount(0);
});

test('a refused choice leaves the person on the current entity with a plain message', async ({ page }) => {
  await mockIdentityProvider(page);
  const api = await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');
  await expect(page.getByText('A-CASE-1001')).toBeVisible();

  api.refuse.add(B.tenantId);
  await switchTo(page, 'Race Entity B');

  await expect(page.getByRole('alert')).toContainText(/could not switch to Race Entity B/i);
  await expect(page.getByText('A-CASE-1001')).toBeVisible();
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity A/);
  expect(await page.evaluate(() => sessionStorage.getItem('lexavault.oidc.selected-tenant'))).toBe(A.tenantId);
});

test('unsaved work on screen asks for confirmation before switching', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');
  await workspaceNav(page).getByRole('link', { name: 'New case' }).click();
  await page.locator('input[type="file"]').setInputFiles({ name: 'unsent.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 test') });
  await expect(page.getByText('unsent.pdf')).toBeVisible();

  await switchTo(page, 'Race Entity B');
  const confirm = page.getByRole('alertdialog');
  await expect(confirm).toContainText(/unsaved or in-progress work/i);
  await confirm.getByRole('button', { name: /stay/i }).click();
  await expect(page.getByText('unsent.pdf')).toBeVisible();
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity A/);

  await switchTo(page, 'Race Entity B');
  await page.getByRole('alertdialog').getByRole('button', { name: /switch anyway/i }).click();
  await expect(switchControl(page)).toHaveAccessibleName(/Race Entity B/);
  await expect(page.getByText('unsent.pdf')).toHaveCount(0);
});

test('the switch works by keyboard', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [A, B]);
  await signInAndChoose(page, 'Race Entity A');
  await expect(page.getByText('A-CASE-1001')).toBeVisible();

  await switchControl(page).focus();
  await page.keyboard.press('Enter');
  const menu = accountMenu(page);
  await expect(menu).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(switchControl(page)).toBeFocused();

  // Arrow keys move between the menu items; Enter on an entity switches to it.
  await page.keyboard.press('Enter');
  const b = entityItems(page).filter({ hasText: 'Race Entity B' });
  await expect(b).toBeVisible();
  for (let step = 0; step < 6; step++) {
    if (await b.evaluate((el) => el === document.activeElement)) break;
    await page.keyboard.press('ArrowDown');
  }
  await expect(b).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByText('B-CASE-2002')).toBeVisible();
});
