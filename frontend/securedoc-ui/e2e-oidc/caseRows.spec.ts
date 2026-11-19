import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U003: the Cases rows. The row's action button does its own job and never also opens the case panel; the
 * rest of the row still opens it; Approve asks for the same confirmation as the case panel.
 *
 * Same harness as errorStates.spec.ts: the real app on the Vite dev server (OIDC mode, Case File design on), with the
 * identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c3c3c3c3-0000-4000-8000-00000000000c', name: 'Rows Entity', slug: 'rows-entity', roles: ['DOCUMENT_SUPERVISOR'] };
const ADMIN_CAPS = ['DASHBOARD_ACCESS', 'REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW', 'TENANT_POLICY_MANAGE'];

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'error-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'error-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'error-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

/** `broken` holds the API paths that currently answer with a shape no screen understands. */

const SUPERVISOR = capabilitiesFor(...ENTITY.roles);
const REVIEW_CASE = caseFor('LN-2001', 'PENDING_REVIEW', '0001');
const APPROVAL_CASE = caseFor('LN-2002', 'PENDING_FINAL_APPROVAL', '0002');

function caseFor(loan: string, status: string, n: string) {
  return {
    packageId: `c3c3c3c3-0000-4000-8000-00000000${n}`, tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status,
    documentCount: 1, createdAt: '2026-11-17T12:00:00Z', updatedAt: '2026-11-17T12:00:00Z', documents: [],
    businessIdentifiers: [{ key: 'loan_number', label: 'Loan number', value: loan, primary: true, searchable: true, displayable: true, displayOrder: 1 }],
  };
}

async function mockApi(page: Page, approvals: string[]) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'rows.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: SUPERVISOR });
    }
    if (url.pathname === '/api/v1/packages') return json([REVIEW_CASE, APPROVAL_CASE]);
    const one = [REVIEW_CASE, APPROVAL_CASE].find((c) => url.pathname === `/api/v1/packages/${c.packageId}`);
    if (one) return json(one);
    if (url.pathname.endsWith('/approve-delivery') && route.request().method() === 'POST') {
      approvals.push(url.pathname);
      return json({ packageId: APPROVAL_CASE.packageId, status: 'COMPLETED' });
    }
    if (url.pathname.startsWith('/api/v1/review-items')) {
      return json({ totalItems: 2, items: ['f1', 'f2'].map((id) => ({ findingId: id, packageId: REVIEW_CASE.packageId, documentId: 'd1', reviewTaskId: 't1',
        entityType: 'SSN', pageNumber: 1, confidence: 0.6, reason: 'LOW_CONFIDENCE', hasBoundingBox: true })) });
    }
    if (url.pathname.includes('/lifecycle')) return json({ packageId: '', successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: false, finalApprovalRequired: true, finalApprovalGranted: false });
    return json({});
  });
}

async function signIn(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
}

const row = (page: Page, loan: string) => page.locator('li.cf-case-row').filter({ hasText: loan });

test('each row shows the next step in words and only the action this person can take', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await expect(row(page, 'LN-2001')).toContainText('2 findings to review');
  await expect(row(page, 'LN-2001').getByRole('button', { name: 'Review LN-2001' })).toBeVisible();
  await expect(row(page, 'LN-2002')).toContainText('Redaction done, ready to approve');
  await expect(row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' })).toBeVisible();
});

test('the Review button opens Review for the case, not the case panel', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await row(page, 'LN-2001').getByRole('button', { name: 'Review LN-2001' }).click();
  await expect(page).toHaveURL((url) => url.pathname === '/review' && url.searchParams.get('case') === REVIEW_CASE.packageId);
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
});

test('Approve asks for the same confirmation as the case panel, approves, and does not open the panel', async ({ page }) => {
  const approvals: string[] = [];
  await mockIdentityProvider(page);
  await mockApi(page, approvals);
  await signIn(page);
  await row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' }).click();
  const dialog = page.getByRole('dialog', { name: 'Release redacted documents?' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('cannot be recalled from the destination');
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(approvals).toHaveLength(0);

  await row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' }).click();
  await page.getByRole('dialog', { name: 'Release redacted documents?' }).getByRole('button', { name: 'Approve delivery' }).click();
  await expect.poll(() => approvals.length).toBe(1);
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
});

test('clicking the rest of the row still opens the case panel', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await row(page, 'LN-2002').getByRole('link').click();
  const panel = page.getByRole('complementary', { name: 'Case panel' });
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Approve delivery' })).toBeVisible();
});
