import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * UI task U006: Reports in plain words, the trend chart with a left axis and the numbers on hover, and the one line a
 * Report Viewer sees instead of the detailed sections (UI spec findings 13 and 32).
 *
 * Same harness as errorStates.spec.ts: the real app on the Vite dev server (OIDC mode, Case File design on), with the
 * identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c6c6c6c6-0000-4000-8000-00000000000c', name: 'Reports Entity', slug: 'reports-entity' };
const ADMIN_CAPS = ['REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW', 'REPORT_EXPORT', 'TENANT_POLICY_MANAGE'];
const VIEWER_CAPS = ['REPORT_EXECUTIVE_VIEW'];

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'reports-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'reports-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'reports-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

async function mockApi(page: Page, roles: string[], capabilities: string[], answer = reports) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'reports.user@example.test', roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug,
        authenticationMode: 'OIDC', capabilities });
    }
    if (url.pathname === '/api/v1/operations/reports') return json(answer());
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
}

/** 30 days from Oct 19; the busiest day (Oct 31) received 23 cases, so the left axis runs 0, 10, 20, 30. */
const TREND = Array.from({ length: 30 }, (_, i) => ({
  bucket: new Date(Date.UTC(2026, 9, 19 + i)).toISOString(),
  received: i === 12 ? 23 : 10 + (i % 5),
  delivered: i === 12 ? 19 : 8 + (i % 4),
}));

function reports() {
  const zeroes = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, 0]));
  return {
    generatedAt: '2026-11-17T20:25:00Z',
    period: { from: '2026-10-19T00:00:00Z', to: '2026-11-18T00:00:00Z', previousFrom: '2026-09-19T00:00:00Z', previousTo: '2026-10-19T00:00:00Z', source: 'ALL', outcome: 'ALL' },
    executive: {
      ...zeroes(['previousBatches', 'completed', 'previousCompleted', 'previousDelivered', 'periodIntakeDelivered', 'previousPeriodIntakeDelivered',
        'completionRate', 'periodIntakeDeliveryRate']),
      batches: 512, documents: 1873, pages: 14220, delivered: 463, deliveryRate: 0.9, medianDeliveryHours: 2.4, p95DeliveryHours: 9.8,
      straightThroughRate: 0.68, reviewRate: 0.29, currentBacklog: 23, currentAttention: 4, oldestBacklogHours: 30.5, trend: TREND,
    },
    intake: zeroes(['totalBatches', 'manual', 'api', 'watchedFolder', 'scanner', 'waitingForTrigger', 'duplicateObservations', 'rejectedObservations']),
    processing: zeroes(['processing', 'needsReview', 'needsApproval', 'completed', 'technicalAttention', 'rejected']),
    export: zeroes(['exportedPackages', 'successfulExportCycles', 'pendingFinalApproval']),
    retention: zeroes(['originalsDueWithin24Hours', 'redactedCopiesDueWithin24Hours', 'technicalHoldsDueWithin24Hours', 'originalsOverdue',
      'redactedCopiesOverdue', 'purgedPackages']),
    timing: zeroes(['medianTurnaroundHours', 'p95TurnaroundHours', 'medianActiveProcessingHours', 'p95ActiveProcessingHours', 'medianReviewWaitHours',
      'medianReviewHandlingHours', 'turnaroundSamples', 'activeProcessingSamples', 'reviewWaitSamples', 'reviewHandlingSamples']),
    backlog: zeroes(['underOneHour', 'oneToFourHours', 'fourToTwentyFourHours', 'oneToThreeDays', 'overThreeDays', 'oldestHours']),
    usage: zeroes(['packages', 'documents', 'pages', 'averageDocumentsPerPackage', 'averagePagesPerPackage', 'reviewedPackages',
      'straightThroughPackages', 'reprocessedPackages']),
    serviceLevel: {
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, deliveryWithinTargetRate: 91, deliveryEligible: 463, deliveryBreaches: 42,
      straightThroughTargetPercent: 70, straightThroughActualPercent: 68,
      reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, reviewWithinTargetRate: 83, reviewEligible: 148, reviewBreaches: 25,
    },
    dimensions: [],
  };
}

async function openReports(page: Page, roles: string[], capabilities: string[], answer = reports) {
  await mockIdentityProvider(page);
  await mockApi(page, roles, capabilities, answer);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Reports' }).click();
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
}

test('the headline and service levels use the plain words', async ({ page }) => {
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS);
  const headline = page.locator('.cf-rp-stats').first();
  for (const words of ['Cases received', 'Delivered', 'Typical time to delivery', '2.4 h', 'slowest 5%: 9.8 h', 'No review needed', '68%',
    '29% needed a person', 'Open now', '4 stopped · oldest 30.5 h']) {
    await expect(headline.getByText(words, { exact: true })).toBeVisible();
  }
  await expect(page.getByText('Targets are set in Admin › Redaction & retention › Reporting')).toBeVisible();
  await expect(page.getByText('Delivered within 4 h')).toBeVisible();
  await expect(page.getByText('42 of 463 cases missed the target')).toBeVisible();
  await expect(page.getByText('Cases delivered without a person reviewing')).toBeVisible();
  const body = await page.locator('.cf-reports').innerText();
  for (const old of [/median/i, /\bp95\b/i, /straight-through/i, /open backlog/i, /\bdesk\b/i, /needs attention/i]) expect(body).not.toMatch(old);
});

test('the trend chart has a left axis with values, dates at regular intervals, and the numbers on hover', async ({ page }) => {
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS);
  const chart = page.getByTestId('trend-chart');
  await expect(chart.locator('.cf-rp-yaxis span')).toHaveText(['0', '10', '20', '30']);
  // 30 days: a date every 5th day, starting with the first.
  const dates = (await chart.locator('.cf-rp-xaxis span').allTextContents()).map((t) => t.trim());
  expect(dates).toHaveLength(30);
  expect(dates.filter(Boolean)).toEqual(['Oct 19', 'Oct 24', 'Oct 29', 'Nov 3', 'Nov 8', 'Nov 13']);
  expect(dates.map((d, i) => (d ? i : -1)).filter((i) => i >= 0)).toEqual([0, 5, 10, 15, 20, 25]);
  await expect(page.getByTestId('trend-tip')).toHaveCount(0);
  await chart.locator('.cf-rp-trend-col').nth(12).hover();
  const tip = page.getByTestId('trend-tip');
  await expect(tip).toBeVisible();
  await expect(tip).toContainText('Oct 31');
  await expect(tip).toContainText('23 received');
  await expect(tip).toContainText('19 delivered');
  await page.getByRole('heading', { name: 'How the work is going' }).hover();
  await expect(tip).toHaveCount(0);
  // The same numbers are in a table for screen readers: a header row and one row per day.
  await expect(page.getByRole('table', { name: 'Cases received and delivered per day' }).getByRole('row')).toHaveCount(31);
});

test('a Report Viewer sees the headline, trend and service levels, and one line instead of the detail', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS);
  await expect(page.getByText('Typical time to delivery')).toBeVisible();
  await expect(page.getByTestId('trend-chart')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Service levels' })).toBeVisible();
  await expect(page.getByText('More detail is available to Business Administrators.')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Pipeline' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Backlog age' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Export CSV' })).toHaveCount(0);
});

test('a Business Administrator sees the detail and Export CSV, not the Report Viewer line', async ({ page }) => {
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS);
  await expect(page.getByRole('region', { name: 'Pipeline' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible();
  await expect(page.getByText('More detail is available to Business Administrators.')).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Pipeline' }).getByText('Stopped', { exact: true })).toBeVisible();
});

test('a period with nothing to measure says so in a neutral colour, not a red 0%', async ({ page }) => {
  const empty = () => {
    const r = reports();
    r.executive.delivered = 0;
    Object.assign(r.serviceLevel, { deliveryWithinTargetRate: 0, deliveryEligible: 0, deliveryBreaches: 0, straightThroughActualPercent: 0,
      reviewWithinTargetRate: 0, reviewEligible: 0, reviewBreaches: 0 });
    return r;
  };
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, empty);
  const levels = page.getByRole('region', { name: 'Service levels' });
  await expect(levels.getByText('No deliveries in this period')).toHaveCount(2);
  await expect(levels.getByText('No reviews in this period')).toBeVisible();
  await expect(levels.getByText('target 95%')).toBeVisible();
  await expect(levels.getByRole('meter')).toHaveCount(0);
  await expect(levels.getByText(/^0%/)).toHaveCount(0);
  await expect(levels.getByText(/0 of 0/)).toHaveCount(0);
  // Neutral: none of the three uses the danger colour.
  const danger = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--cf-danger').trim());
  const colours = await levels.locator('.cf-rp-target-value').evaluateAll((els) => els.map((e) => getComputedStyle(e).color));
  const probe = await page.evaluate((c) => { const d = document.createElement('span'); d.style.color = c; document.body.append(d); const v = getComputedStyle(d).color; d.remove(); return v; }, danger);
  expect(colours).toHaveLength(3);
  for (const c of colours) expect(c).not.toBe(probe);
});

test('a period with deliveries and reviews still shows the measured bars', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS);
  const levels = page.getByRole('region', { name: 'Service levels' });
  await expect(levels.getByRole('meter')).toHaveCount(3);
  await expect(levels.getByText(/in this period/)).toHaveCount(0);
  await expect(levels.getByText('25 of 148 reviews waited longer')).toBeVisible();
});

test('the review bar measures against the review target set in Admin, not 100%', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS);
  const levels = page.getByRole('region', { name: 'Service levels' });
  await expect(levels.getByRole('meter', { name: 'Review picked up within 1 h: 83% against a 80% target' })).toBeVisible();
  await expect(levels.getByText('target 100%')).toHaveCount(0);
});

test('an answer without a readable review target fails only the service levels card', async ({ page }) => {
  const missing = () => { const r = reports(); delete (r.serviceLevel as Partial<typeof r.serviceLevel>).reviewWaitTargetPercent; return r; };
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, missing);
  await expect(page.getByRole('alert', { name: "Service levels couldn't be shown" })).toBeVisible();
  await expect(page.getByText('Typical time to delivery')).toBeVisible();
  await expect(page.getByTestId('trend-chart')).toBeVisible();
});
