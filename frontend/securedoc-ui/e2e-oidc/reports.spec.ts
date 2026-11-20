import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U006: Reports in plain words, the trend chart with a left axis and the numbers on hover, and the one line a
 * Report Viewer sees instead of the detailed sections (UI spec findings 13 and 32).
 *
 * Same harness as errorStates.spec.ts: the real app on the Vite dev server (OIDC mode, Case File design on), with the
 * identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c6c6c6c6-0000-4000-8000-00000000000c', name: 'Reports Entity', slug: 'reports-entity' };
const ADMIN_CAPS = capabilitiesFor('BUSINESS_ADMIN');
const VIEWER_CAPS = capabilitiesFor('REPORT_VIEWER');

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

async function mockApi(page: Page, roles: string[], capabilities: string[], answer: (url: URL) => unknown = reports) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'reports.user@example.test', roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug,
        authenticationMode: 'OIDC', capabilities });
    }
    if (url.pathname === '/api/v1/operations/reports') return json(answer(url));
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
    timeSaved: { casesDelivered: 512, casesNoPerson: 348, percentNoPerson: 67, pagesChecked: 14220, documentsChecked: 1873, itemsRedacted: 3912, itemTypes: ['Social Security Number', 'Date of birth', 'Credit card number', 'Credit score'], pagesNoPerson: 9690, minutesPerPage: 1, minutesSaved: 9690 },
  };
}

async function openReports(page: Page, roles: string[], capabilities: string[], answer: (url: URL) => unknown = reports) {
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
  await expect(page.getByText('Cases delivered with no person reviewing or finishing them')).toBeVisible();
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

// --- U024: What LexaVault did for you ------------------------------------------------------------------------------

const withTimeSaved = (t: Record<string, unknown>) => () => { const r = reports(); return { ...r, timeSaved: { ...r.timeSaved, ...t } }; };
const didForYou = (page: Page) => page.getByRole('region', { name: 'What LexaVault did for you' });
const howTitle = 'How "Review time saved" is worked out';
const cssColour = (page: Page, token: string) => page.evaluate((t) => {
  const d = document.createElement('span');
  d.style.color = `var(${t})`;
  document.querySelector('.cf')!.append(d);
  const v = getComputedStyle(d).color;
  d.remove();
  return v;
}, token);

test('U024: four tiles above the headline, in the canvas words and borders', async ({ page }) => {
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS);
  const section = didForYou(page);
  await expect(section.getByRole('heading', { level: 2, name: 'What LexaVault did for you' })).toBeVisible();
  const tiles = section.locator('.cf-rp-did-tile');
  expect(await tiles.allInnerTexts()).toEqual([
    'Handled without a person\n67%\n348 of 512 cases',
    'Pages checked\n14,220\nin 1,873 delivered documents',
    'Sensitive items redacted\n3,912\nSSN, date of birth, card, credit score',
    'Review time saved (estimate)\n≈ 161 h\nHow is this worked out?',
  ]);
  // Under the title row, above the existing headline tiles.
  const sectionBox = (await section.boundingBox())!;
  const headlineBox = (await page.locator('dl.cf-rp-stats').first().boundingBox())!;
  expect(sectionBox.y + sectionBox.height).toBeLessThanOrEqual(headlineBox.y);
  // First tile: a 4 px bottom border in the success colour; the time saved tile: a 2 px dark border.
  const ok = await cssColour(page, '--cf-ok');
  const ink = await cssColour(page, '--cf-ink');
  const first = await tiles.nth(0).evaluate((el) => { const s = getComputedStyle(el); return [s.borderBottomWidth, s.borderBottomColor]; });
  expect(first).toEqual(['4px', ok]);
  const last = await tiles.nth(3).evaluate((el) => { const s = getComputedStyle(el); return [s.borderTopWidth, s.borderTopColor]; });
  expect(last).toEqual(['2px', ink]);
  const big = await tiles.nth(0).locator('.cf-rp-did-value').evaluate((el) => getComputedStyle(el).fontSize);
  expect(big).toBe('32px');
});

test('U024: no delivered cases: 0 and "—", and the panel still opens', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, withTimeSaved({ casesDelivered: 0, casesNoPerson: 0, percentNoPerson: null,
    pagesChecked: 0, documentsChecked: 0, itemsRedacted: 0, itemTypes: [], pagesNoPerson: 0, minutesSaved: 0 }));
  const tiles = didForYou(page).locator('.cf-rp-did-tile');
  expect(await tiles.allInnerTexts()).toEqual([
    'Handled without a person\n—\n0 of 0 cases',
    'Pages checked\n0\nin 0 delivered documents',
    'Sensitive items redacted\n0\n—',
    'Review time saved (estimate)\n0 min\nHow is this worked out?',
  ]);
  await didForYou(page).getByRole('button', { name: 'How is this worked out?' }).click();
  const panel = page.getByRole('dialog', { name: howTitle });
  expect(await panel.getByRole('cell').allInnerTexts()).toEqual([
    'Pages in cases that needed no person', '0', '× minutes a person would take per page', '1', '= 0 minutes, rounded down', '0 min']);
});

test('U024: under an hour the estimate reads in minutes', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, withTimeSaved({ pagesNoPerson: 45, minutesSaved: 45 }));
  await expect(didForYou(page).locator('.cf-rp-did-tile').nth(3).locator('.cf-rp-did-value')).toHaveText('≈ 45 min');
});

test('U024: "How is this worked out?": the numbers, "deliberately low" at 1 minute, focus, Esc and Close', async ({ page }) => {
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS);
  const section = didForYou(page);
  const link = section.getByRole('button', { name: 'How is this worked out?' });
  await expect(link).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('dialog', { name: howTitle })).toHaveCount(0);
  await link.click();
  const panel = page.getByRole('dialog', { name: howTitle });
  await expect(panel).toBeVisible();
  await expect(panel).toBeFocused();
  await expect(link).toHaveAttribute('aria-expanded', 'true');
  await expect(panel.getByRole('heading', { level: 3 })).toHaveText(howTitle);
  expect(await panel.getByRole('cell').allInnerTexts()).toEqual([
    'Pages in cases that needed no person', '9,690', '× minutes a person would take per page', '1', '= 9,690 minutes, rounded down', '≈ 161 h']);
  const mono = await panel.getByRole('cell').nth(1).evaluate((el) => [getComputedStyle(el).fontFamily, getComputedStyle(el).textAlign]);
  expect(mono[0]).toContain('IBM Plex Mono');
  expect(mono[1]).toBe('right');
  await expect(panel.getByText('Pages a person reviewed count as zero. The 1 minute per page is deliberately low and is set by your Business '
    + 'Administrator in Admin › Redaction & retention › Report targets. This is an estimate, not a measurement.', { exact: true })).toBeVisible();
  // Right-aligned under the tiles, at most 460 px wide.
  const p = (await panel.boundingBox())!;
  const tiles = (await section.locator('.cf-rp-did-tiles').boundingBox())!;
  expect(p.width).toBeLessThanOrEqual(460);
  expect(p.y).toBeGreaterThanOrEqual(tiles.y + tiles.height);
  expect(Math.abs(p.x + p.width - (tiles.x + tiles.width))).toBeLessThanOrEqual(1);

  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
  await expect(link).toBeFocused();
  await expect(link).toHaveAttribute('aria-expanded', 'false');

  await page.keyboard.press('Enter');
  await expect(panel).toBeFocused();
  await panel.getByRole('button', { name: 'Close' }).click();
  await expect(panel).toHaveCount(0);
  await expect(link).toBeFocused();
});

test('U024: at 2.5 minutes per page the panel names it, without "deliberately low"', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, withTimeSaved({ pagesNoPerson: 121, minutesPerPage: 2.5, minutesSaved: 302 }));
  await expect(didForYou(page).locator('.cf-rp-did-tile').nth(3).locator('.cf-rp-did-value')).toHaveText('≈ 5 h');
  await didForYou(page).getByRole('button', { name: 'How is this worked out?' }).click();
  const panel = page.getByRole('dialog', { name: howTitle });
  expect(await panel.getByRole('cell').allInnerTexts()).toEqual([
    'Pages in cases that needed no person', '121', '× minutes a person would take per page', '2.5', '= 302.5 minutes, rounded down', '≈ 5 h']);
  await expect(panel.getByText('Pages a person reviewed count as zero. The 2.5 minutes per page is set by your Business Administrator in '
    + 'Admin › Redaction & retention › Report targets. This is an estimate, not a measurement.', { exact: true })).toBeVisible();
  await expect(panel).not.toContainText('deliberately low');
});

test('U024: the tiles follow the chosen period and filters', async ({ page }) => {
  const asked: URLSearchParams[] = [];
  await openReports(page, ['BUSINESS_ADMIN'], ADMIN_CAPS, (url) => {
    asked.push(url.searchParams);
    return url.searchParams.get('source') === 'API' ? withTimeSaved({ pagesChecked: 7, documentsChecked: 2 })() : reports();
  });
  const pages = didForYou(page).locator('.cf-rp-did-tile').nth(1);
  await expect(pages).toContainText('14,220');
  await page.getByLabel('Source').selectOption('API');
  await expect(pages.locator('.cf-rp-did-value')).toHaveText('7');
  await expect(pages).toContainText('in 2 delivered documents');
  expect(asked.at(-1)!.get('source')).toBe('API');
});

test('U024: an answer without the block fails only this card', async ({ page }) => {
  const missing = () => { const r: Partial<ReturnType<typeof reports>> = reports(); delete r.timeSaved; return r; };
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS, missing);
  await expect(page.getByRole('alert', { name: "What LexaVault did for you couldn't be shown" })).toBeVisible();
  await expect(page.getByText('Typical time to delivery')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Service levels' })).toBeVisible();
});

test('U024: the Report Viewer sees the section (the Business Administrator in the tests above)', async ({ page }) => {
  await openReports(page, ['REPORT_VIEWER'], VIEWER_CAPS);
  await expect(didForYou(page)).toBeVisible();
  await expect(didForYou(page).getByRole('button', { name: 'How is this worked out?' })).toBeVisible();
});

for (const role of ['DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'TENANT_IT_ADMIN', 'PLATFORM_OPERATIONS_ADMIN']) {
  test(`U024: ${role} does not see the section (no Reports at all)`, async ({ page }) => {
    let asked = 0;
    await mockIdentityProvider(page);
    await mockApi(page, [role], capabilitiesFor(role), () => { asked++; return reports(); });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    const nav = page.getByRole('navigation', { name: 'Workspace' });
    await expect(nav).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Reports' })).toHaveCount(0);
    await page.evaluate(() => { window.history.pushState({}, '', '/reports'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'What LexaVault did for you' })).toHaveCount(0);
    expect(asked).toBe(0);
  });
}
