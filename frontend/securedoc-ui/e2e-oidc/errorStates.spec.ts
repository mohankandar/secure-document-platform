import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U001: an answer a screen does not understand never blanks the app (UI spec findings 8 and 22), and a
 * person with no LexaVault role sees the no-access page (finding 21).
 *
 * Same harness as signinRace.spec.ts and entitySwitch.spec.ts: the real app on the Vite dev server (OIDC mode,
 * Case File design on), with the identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c3c3c3c3-0000-4000-8000-00000000000c', name: 'Error Entity', slug: 'error-entity', roles: ['BUSINESS_ADMIN', 'DOCUMENT_SUPERVISOR'] };
// A real combination that reaches Cases, Reports and Admin, with the policy's lists (U018 follow-up).
const ADMIN_CAPS = capabilitiesFor(...ENTITY.roles);

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
async function mockApi(page: Page, capabilities: string[], broken: Set<string>) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'error.user@example.test', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug,
        authenticationMode: 'OIDC', capabilities });
    }
    if (broken.has(url.pathname)) return json({ unexpected: 'shape', items: 'not-a-list' });
    if (url.pathname === '/api/v1/packages') return json([caseFor('LN-1001')]);
    if (url.pathname === '/api/v1/operations/reports') {
      const body = reports();
      // A report whose overall shape is right but one trend entry is broken: only the trend card may fail.
      if (broken.has('trend')) body.executive.trend = [null as never];
      return json(body);
    }
    if (url.pathname.startsWith('/api/v1/review-items')) return json({ items: [], totalItems: 0 });
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
}

function caseFor(loanNumber: string) {
  return {
    packageId: 'c3c3c3c3-0000-4000-8000-000000001001', tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status: 'COMPLETED',
    documentCount: 1, createdAt: '2026-11-17T12:00:00Z', updatedAt: '2026-11-17T12:00:00Z',
    businessIdentifiers: [{ key: 'loan_number', label: 'Loan number', value: loanNumber, primary: true, searchable: true, displayable: true, displayOrder: 1 }],
  };
}

function reports() {
  const zeroes = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, 0]));
  return {
    generatedAt: '2026-11-17T12:00:00Z',
    period: { from: '2026-10-18T00:00:00Z', to: '2026-11-17T00:00:00Z', previousFrom: '2026-09-18T00:00:00Z', previousTo: '2026-10-18T00:00:00Z', source: 'ALL', outcome: 'ALL' },
    executive: { ...zeroes(['batches', 'previousBatches', 'documents', 'pages', 'completed', 'previousCompleted', 'delivered', 'previousDelivered',
      'periodIntakeDelivered', 'previousPeriodIntakeDelivered', 'completionRate', 'deliveryRate', 'periodIntakeDeliveryRate', 'medianDeliveryHours',
      'p95DeliveryHours', 'straightThroughRate', 'reviewRate', 'currentBacklog', 'currentAttention', 'oldestBacklogHours']), trend: [] },
    intake: zeroes(['totalBatches', 'manual', 'api', 'watchedFolder', 'scanner', 'waitingForTrigger', 'duplicateObservations', 'rejectedObservations']),
    processing: zeroes(['processing', 'needsReview', 'needsApproval', 'completed', 'technicalAttention', 'rejected']),
    export: zeroes(['exportedPackages', 'successfulExportCycles', 'pendingFinalApproval']),
    retention: zeroes(['originalsDueWithin24Hours', 'redactedDueWithin24Hours', 'purgedInPeriod', 'onHold']),
    timing: zeroes(['medianTurnaroundHours', 'p95TurnaroundHours', 'medianActiveProcessingHours', 'p95ActiveProcessingHours', 'medianReviewWaitHours',
      'medianReviewHandlingHours', 'turnaroundSamples', 'activeProcessingSamples', 'reviewWaitSamples', 'reviewHandlingSamples']),
    backlog: zeroes(['underOneHour', 'oneToFourHours', 'fourToTwentyFourHours', 'oneToThreeDays', 'overThreeDays', 'oldestHours']),
    usage: zeroes(['packages', 'documents', 'pages', 'averageDocumentsPerPackage', 'averagePagesPerPackage', 'reviewedPackages', 'straightThroughPackages', 'reprocessedPackages']),
    serviceLevel: zeroes(['deliveryTargetMinutes', 'deliveryTargetPercent', 'deliveryWithinTargetRate', 'deliveryEligible', 'deliveryBreaches',
      'straightThroughTargetPercent', 'straightThroughActualPercent', 'reviewWaitTargetMinutes', 'reviewWaitTargetPercent', 'reviewWithinTargetRate', 'reviewEligible', 'reviewBreaches']),
    dimensions: [],
    timeSaved: { casesDelivered: 512, casesNoPerson: 348, percentNoPerson: 67, pagesChecked: 14220, documentsChecked: 1873, itemsRedacted: 3912, itemTypes: ['Social Security Number', 'Date of birth', 'Credit card number', 'Credit score'], pagesNoPerson: 9690, minutesPerPage: 1, minutesSaved: 9690 },
  };
}

async function signIn(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
}

const workspaceNav = (page: Page) => page.getByRole('navigation', { name: 'Workspace' });

test('Reports with an answer of the wrong shape shows the error card; the top bar and other screens keep working', async ({ page }) => {
  const broken = new Set(['/api/v1/operations/reports']);
  await mockIdentityProvider(page);
  await mockApi(page, ADMIN_CAPS, broken);
  await signIn(page);
  await expect(page.getByText('LN-1001')).toBeVisible();

  await workspaceNav(page).getByRole('link', { name: 'Reports' }).click();
  const card = page.getByRole('alert', { name: "Reports couldn't be loaded" });
  await expect(card).toBeVisible();
  await expect(card).toContainText('sent an answer this screen doesn’t understand');
  await expect(workspaceNav(page)).toBeVisible();

  // Codes stay under the collapsed Technical details.
  const details = card.getByText('Technical details');
  await expect(card.getByText('unexpected response shape')).toBeHidden();
  await details.click();
  await expect(card.getByText('unexpected response shape')).toBeVisible();
  await expect(card.getByText('GET /operations/reports')).toBeVisible();
  await expect(card.getByText(/^ui-[0-9a-f]{8}$/)).toBeVisible();

  // Once the server answers properly, Try again shows the report.
  broken.clear();
  await card.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByRole('alert', { name: "Reports couldn't be loaded" })).toBeHidden();
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();

  // Another screen still works.
  await workspaceNav(page).getByRole('link', { name: 'Cases' }).click();
  await expect(page.getByText('LN-1001')).toBeVisible();
});

test('a card that fails while drawing shows its own error card; the other report cards still show', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, ADMIN_CAPS, new Set(['trend']));
  await signIn(page);
  await workspaceNav(page).getByRole('link', { name: 'Reports' }).click();
  await expect(page.getByRole('alert', { name: "The trend couldn't be shown" })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  await expect(page.getByText('Cases received')).toBeVisible();
  await expect(workspaceNav(page)).toBeVisible();
});

test('Admin policy with an answer of the wrong shape shows the error card and keeps the top bar and the tabs', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, ADMIN_CAPS, new Set(['/api/v1/business-admin/configuration']));
  await signIn(page);
  await workspaceNav(page).getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByRole('alert', { name: /couldn't be loaded/ })).toBeVisible();
  await expect(workspaceNav(page)).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Administration areas' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Administration' })).toBeVisible();
});

test('Cases with an answer of the wrong shape shows the error card, not an empty list', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, ADMIN_CAPS, new Set(['/api/v1/packages']));
  await signIn(page);
  await expect(page.getByRole('alert', { name: "Your cases couldn't be loaded" })).toBeVisible();
  await expect(workspaceNav(page).getByRole('link', { name: 'Reports' })).toBeVisible();
});

test('a person with no LexaVault role sees the no-access page with Sign out and Try again', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [], new Set());
  await signIn(page);
  await expect(page.getByRole('heading', { name: "You don't have access to anything in LexaVault yet" })).toBeVisible();
  await expect(page.getByText('error.user@example.test')).toBeVisible();
  await expect(page.getByText('Ask your administrator to add you to a LexaVault group, then sign in again.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
});
