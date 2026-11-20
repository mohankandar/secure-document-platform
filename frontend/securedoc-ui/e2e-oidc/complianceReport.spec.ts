import { test, expect, type Download, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U022: the Compliance report dialog on Reports (Business Administrator, summary edition) and Admin › Audit
 * trail (IT Administrator, full audit trail edition): who sees the button, the request body, busy, success and error
 * states, the Custom period checks, and focus.
 *
 * Same harness as reports.spec.ts: the real app on the Vite dev server (OIDC mode, Case File design on), with the
 * identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c7c7c7c7-0000-4000-8000-00000000000c', name: 'Residential lending', slug: 'residential-lending' };
const FILE = 'compliance-report-residential-lending-2026-07-01-2026-09-30.pdf';
const PDF = Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n', 'latin1');

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'compliance-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'compliance-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'compliance-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

function reports() {
  const zeroes = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, 0]));
  return {
    generatedAt: '2026-11-20T08:00:00Z',
    period: { from: '2026-10-21T00:00:00Z', to: '2026-11-20T00:00:00Z', previousFrom: '2026-09-21T00:00:00Z', previousTo: '2026-10-21T00:00:00Z', source: 'ALL', outcome: 'ALL' },
    executive: {
      ...zeroes(['previousBatches', 'completed', 'previousCompleted', 'previousDelivered', 'periodIntakeDelivered', 'previousPeriodIntakeDelivered', 'completionRate', 'periodIntakeDeliveryRate']),
      batches: 1512, documents: 5100, pages: 40220, delivered: 1431, deliveryRate: 0.95, medianDeliveryHours: 2.4, p95DeliveryHours: 9.8,
      straightThroughRate: 0.68, reviewRate: 0.29, currentBacklog: 23, currentAttention: 0, oldestBacklogHours: 30.5,
      trend: [{ bucket: '2026-11-18T00:00:00Z', received: 52, delivered: 50 }, { bucket: '2026-11-19T00:00:00Z', received: 48, delivered: 47 }],
    },
    intake: zeroes(['totalBatches', 'manual', 'api', 'watchedFolder', 'scanner', 'waitingForTrigger', 'duplicateObservations', 'rejectedObservations']),
    processing: zeroes(['processing', 'needsReview', 'needsApproval', 'completed', 'technicalAttention', 'rejected']),
    export: zeroes(['exportedPackages', 'successfulExportCycles', 'pendingFinalApproval']),
    retention: zeroes(['originalsDueWithin24Hours', 'redactedCopiesDueWithin24Hours', 'technicalHoldsDueWithin24Hours', 'originalsOverdue', 'redactedCopiesOverdue', 'purgedPackages']),
    timing: zeroes(['medianTurnaroundHours', 'p95TurnaroundHours', 'medianActiveProcessingHours', 'p95ActiveProcessingHours', 'medianReviewWaitHours',
      'medianReviewHandlingHours', 'turnaroundSamples', 'activeProcessingSamples', 'reviewWaitSamples', 'reviewHandlingSamples']),
    backlog: zeroes(['underOneHour', 'oneToFourHours', 'fourToTwentyFourHours', 'oneToThreeDays', 'overThreeDays', 'oldestHours']),
    usage: zeroes(['packages', 'documents', 'pages', 'averageDocumentsPerPackage', 'averagePagesPerPackage', 'reviewedPackages', 'straightThroughPackages', 'reprocessedPackages']),
    serviceLevel: {
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, deliveryWithinTargetRate: 91, deliveryEligible: 1431, deliveryBreaches: 42,
      straightThroughTargetPercent: 70, straightThroughActualPercent: 68,
      reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, reviewWithinTargetRate: 83, reviewEligible: 148, reviewBreaches: 25,
    },
    dimensions: [],
    timeSaved: { casesDelivered: 512, casesNoPerson: 348, percentNoPerson: 67, pagesChecked: 14220, documentsChecked: 1873, itemsRedacted: 3912, itemTypes: ['Social Security Number', 'Date of birth', 'Credit card number', 'Credit score'], pagesNoPerson: 9690, minutesPerPage: 1, minutesSaved: 9690 },
  };
}

type Answer = { status: number; delayMs?: number };
type Mock = {
  bodies: unknown[]; answer: Answer; release?: () => void;
  /** Task 0019 A1: GET /audit-records/count. Each request's query, and the answer by period. */
  countQueries?: Array<Record<string, string>>;
  count?: (q: Record<string, string>) => { status: number; body?: unknown; delayMs?: number };
};

async function mockApi(page: Page, role: string, mock: Mock) {
  const capabilities = capabilitiesFor(role);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: [role] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'compliance.user@example.test', roles: [role], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities });
    }
    if (url.pathname === '/api/v1/operations/reports') return json(reports());
    if (url.pathname === '/api/v1/audit-records') return json([]);
    if (url.pathname === '/api/v1/audit-records/count') {
      const q = Object.fromEntries(url.searchParams.entries());
      mock.countQueries?.push(q);
      const answer = mock.count ? mock.count(q) : { status: 404, body: {} };
      if (answer.delayMs) await new Promise((resolve) => setTimeout(resolve, answer.delayMs));
      return json(answer.body ?? {}, answer.status);
    }
    if (url.pathname === '/api/v1/compliance-reports' && request.method() === 'POST') {
      mock.bodies.push(request.postDataJSON());
      if (mock.answer.delayMs) await new Promise<void>((resolve) => { mock.release = resolve; setTimeout(resolve, mock.answer.delayMs); });
      if (mock.answer.status !== 200) {
        return json({ errorCode: mock.answer.status === 403 ? 'ACCESS_DENIED' : 'VALIDATION_FAILED', message: 'refused' }, mock.answer.status);
      }
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: PDF, headers: {
        'Content-Disposition': `attachment; filename="${FILE}"`, 'X-Report-File-SHA256': 'ab'.repeat(32), 'Cache-Control': 'no-store',
      } });
    }
    return json({});
  });
}

async function signIn(page: Page, role: string, mock: Mock) {
  await mockIdentityProvider(page);
  await mockApi(page, role, mock);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.locator('#cf-main h1').first().waitFor();
}

async function openReports(page: Page, role: string, mock: Mock) {
  await signIn(page, role, mock);
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Reports' }).click();
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
}

async function openAudit(page: Page, role: string, mock: Mock) {
  await signIn(page, role, mock);
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Audit trail/ }).click();
  await expect(page.getByRole('region', { name: 'Audit records' })).toBeVisible();
}

const newMock = (answer: Answer = { status: 200 }): Mock => ({ bodies: [], answer });
const button = (page: Page) => page.getByRole('button', { name: 'Compliance report', exact: true });
const dialog = (page: Page) => page.getByRole('dialog', { name: 'Compliance report' });
const timeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/** Waits for the download to be saved, then removes it (Edge closes badly if a test ends with one still open). */
async function finished(download: Promise<Download>): Promise<string> {
  const d = await download;
  await d.path();
  const name = d.suggestedFilename();
  await d.delete();
  return name;
}

/** Today and the Last quarter range in the browser's zone, worked out the same way as the server. */
async function browserFacts(page: Page) {
  return page.evaluate(() => {
    const now = new Date();
    const q = Math.floor(now.getMonth() / 3) * 3;
    const from = new Date(now.getFullYear(), q - 3, 1);
    const to = new Date(now.getFullYear(), q, 0);
    const m = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const label = from.getFullYear() === to.getFullYear()
      ? `${m[from.getMonth()]} ${from.getDate()} – ${m[to.getMonth()]} ${to.getDate()}, ${to.getFullYear()}`
      : `${m[from.getMonth()]} ${from.getDate()}, ${from.getFullYear()} – ${m[to.getMonth()]} ${to.getDate()}, ${to.getFullYear()}`;
    const day = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return { label, today: day(now), zone: Intl.DateTimeFormat().resolvedOptions().timeZone };
  });
}

test('who sees the button: Business Administrator on Reports, IT Administrator on Audit trail; not Report Viewer or Platform Operations', async ({ page, context }) => {
  await openReports(page, 'BUSINESS_ADMIN', newMock());
  const actions = page.locator('.cf-rp-actions');
  await expect(actions.getByRole('button')).toHaveText(['Export CSV', 'Compliance report']);

  const viewer = await context.newPage();
  await openReports(viewer, 'REPORT_VIEWER', newMock());
  await expect(button(viewer)).toHaveCount(0);
  await viewer.close();

  const it = await context.newPage();
  await openAudit(it, 'TENANT_IT_ADMIN', newMock());
  const bar = it.locator('.cf-ad-audit-bar');
  await expect(bar.getByRole('button', { name: 'Export' })).toBeVisible();
  // Right of Export.
  const exportBox = await bar.getByRole('button', { name: 'Export' }).boundingBox();
  const reportBox = await button(it).boundingBox();
  expect(reportBox!.x).toBeGreaterThan(exportBox!.x);
  await it.close();

  const ops = await context.newPage();
  await openAudit(ops, 'PLATFORM_OPERATIONS_ADMIN', newMock());
  await expect(button(ops)).toHaveCount(0);
  await ops.close();
});

test('Business Administrator: the summary dialog, Last quarter chosen, the body it sends, the download and the notice', async ({ page }) => {
  const mock = newMock();
  await openReports(page, 'BUSINESS_ADMIN', mock);
  await button(page).click();
  const d = dialog(page);
  await expect(d).toBeVisible();
  await expect(d).toHaveAttribute('aria-modal', 'true');
  await expect(d.getByText('Residential lending · one entity per report')).toBeVisible();
  const facts = await browserFacts(page);
  await expect(d.getByRole('button', { name: 'Last quarter' })).toHaveAttribute('aria-pressed', 'true');
  for (const other of ['Last month', 'Last year', 'Custom']) await expect(d.getByRole('button', { name: other })).toHaveAttribute('aria-pressed', 'false');
  await expect(d.locator('.cf-cr-range')).toHaveText(facts.label);
  await expect(d.getByRole('group', { name: 'Period' })).toBeVisible();
  for (const line of ['Redaction policy as it stood each day, and every change: who, when, old → new', 'Keeping and deleting settings, and proof that deletions ran',
    'Case outcomes and review numbers', 'Audit activity, as counts']) await expect(d.getByRole('listitem').filter({ hasText: line })).toBeVisible();
  await expect(d.getByText('The full audit trail is added by your IT Administrator, from Admin › Audit trail.')).toBeVisible();
  await expect(d.getByRole('checkbox')).toHaveCount(0);
  await expect(d.getByText('Contains no borrower names, file names, loan numbers or redacted values.')).toBeVisible();
  await expect(d.getByText('Creating it is recorded in the audit trail.')).toBeVisible();
  for (const name of ['Cancel', 'Create PDF']) expect((await d.getByRole('button', { name }).boundingBox())!.height).toBeGreaterThanOrEqual(44);
  for (const p of ['Last month', 'Last quarter', 'Last year', 'Custom']) expect((await d.getByRole('button', { name: p }).boundingBox())!.height).toBeGreaterThanOrEqual(40);
  expect((await d.boundingBox())!.width).toBeLessThanOrEqual(560);

  const download = page.waitForEvent('download');
  await d.getByRole('button', { name: 'Create PDF' }).click();
  expect(await finished(download)).toBe(FILE);
  expect(mock.bodies).toEqual([{ period: 'LAST_QUARTER', timeZone: facts.zone, includeFullAuditTrail: false }]);
  await expect(d).toBeHidden();
  await expect(page.locator('.cf-toast').filter({ hasText: 'Compliance report downloaded.' })).toBeVisible();
  await expect(button(page)).toBeFocused();
  // Nothing of the report is kept in the browser.
  const kept = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(kept).not.toContain('compliance-report');
  expect(kept).not.toContain('ab'.repeat(32));
});

test('IT Administrator: the full trail is ticked by default and can be unticked; Last year and Last month bodies', async ({ page }) => {
  const mock = newMock();
  await openAudit(page, 'TENANT_IT_ADMIN', mock);
  await button(page).click();
  const d = dialog(page);
  await expect(d.getByText('Everything in the summary edition: policy and its changes, keeping and deleting with proof, case outcomes, audit counts.')).toBeVisible();
  const trail = d.getByRole('checkbox', { name: /Include full audit trail/ });
  await expect(trail).toBeChecked();
  await expect(d.getByText('Every record in the period, added as an appendix. Cases appear by case ID only.')).toBeVisible();
  await d.getByRole('button', { name: 'Last year' }).click();
  await expect(d.locator('.cf-cr-range')).toHaveText(new RegExp(`^Jan 1 – Dec 31, ${new Date().getFullYear() - 1}$`));
  let download = page.waitForEvent('download');
  await d.getByRole('button', { name: 'Create PDF' }).click();
  await finished(download);
  await button(page).click();
  await dialog(page).getByRole('button', { name: 'Last month' }).click();
  await dialog(page).getByRole('checkbox', { name: /Include full audit trail/ }).uncheck();
  download = page.waitForEvent('download');
  await dialog(page).getByRole('button', { name: 'Create PDF' }).click();
  await finished(download);
  expect(mock.bodies).toEqual([
    { period: 'LAST_YEAR', timeZone: timeZone(), includeFullAuditTrail: true },
    { period: 'LAST_MONTH', timeZone: timeZone(), includeFullAuditTrail: false },
  ]);
});

test('0019 A1: the full trail card shows "· N records" for the chosen period; none while loading, on failure or for the summary', async ({ page }) => {
  const mock: Mock = {
    ...newMock(), countQueries: [],
    count: (q) => q.period === 'LAST_QUARTER' ? { status: 200, body: { count: 1204 } }
      : q.period === 'LAST_YEAR' ? { status: 200, body: { count: 1 }, delayMs: 1500 }
      : q.period === 'LAST_MONTH' ? { status: 500, body: { message: 'x' } }
      : { status: 200, body: { count: 37 } },
  };
  await openAudit(page, 'TENANT_IT_ADMIN', mock);
  await button(page).click();
  const d = dialog(page);
  const note = d.getByTestId('cr-trail-note');
  await expect(note).toHaveText('Every record in the period, added as an appendix · 1,204 records. Cases appear by case ID only.');
  await expect(d.getByTestId('cr-record-count')).toHaveText('1,204');
  expect(await d.getByTestId('cr-record-count').evaluate((el) => getComputedStyle(el).fontFamily)).toMatch(/mono/i);
  expect(mock.countQueries![0]).toEqual({ period: 'LAST_QUARTER', timeZone: timeZone() });

  // While the next period's count loads, the card shows without a count (never the old one).
  await d.getByRole('button', { name: 'Last year' }).click();
  await expect(note).toHaveText('Every record in the period, added as an appendix. Cases appear by case ID only.');
  await expect(note).toHaveText('Every record in the period, added as an appendix · 1 record. Cases appear by case ID only.');

  // A failed count: the card without it.
  await d.getByRole('button', { name: 'Last month' }).click();
  await expect.poll(() => mock.countQueries!.some((q) => q.period === 'LAST_MONTH')).toBe(true);
  await expect(note).toHaveText('Every record in the period, added as an appendix. Cases appear by case ID only.');

  // Custom sends from and to; an invalid range asks for nothing.
  await d.getByRole('button', { name: 'Custom' }).click();
  await d.getByLabel('From').fill('2024-01-01');
  await d.getByLabel('To').fill('2024-01-31');
  await expect(d.getByTestId('cr-record-count')).toHaveText('37');
  expect(mock.countQueries!.at(-1)).toEqual({ period: 'CUSTOM', from: '2024-01-01', to: '2024-01-31', timeZone: timeZone() });
  const asked = mock.countQueries!.length;
  await d.getByLabel('To').fill('2025-06-01');
  await expect(d.getByText('The period can be at most 366 days.')).toBeVisible();
  await expect(note).toHaveText('Every record in the period, added as an appendix. Cases appear by case ID only.');
  expect(mock.countQueries!.length).toBe(asked);
});

test('0019 A1: the summary edition never asks for the record count', async ({ page }) => {
  const mock: Mock = { ...newMock(), countQueries: [], count: () => ({ status: 200, body: { count: 5 } }) };
  await openReports(page, 'BUSINESS_ADMIN', mock);
  await button(page).click();
  await expect(dialog(page).getByRole('button', { name: 'Create PDF' })).toBeEnabled();
  await page.waitForTimeout(300);
  expect(mock.countQueries).toEqual([]);
});

test('Custom: From and To in one row; a range ending today or over 366 days says why and disables Create PDF', async ({ page }) => {
  const mock = newMock();
  await openReports(page, 'BUSINESS_ADMIN', mock);
  await button(page).click();
  const d = dialog(page);
  await d.getByRole('button', { name: 'Custom' }).click();
  const from = d.getByLabel('From');
  const to = d.getByLabel('To');
  const [a, b] = [await from.boundingBox(), await to.boundingBox()];
  expect(Math.abs(a!.y - b!.y)).toBeLessThan(2);
  const create = d.getByRole('button', { name: 'Create PDF' });
  await expect(create).toBeEnabled();

  const { today } = await browserFacts(page);
  await to.fill(today);
  await expect(d.getByRole('alert').filter({ hasText: 'The period must end before today.' })).toBeVisible();
  await expect(create).toBeDisabled();
  await expect(to).toHaveAttribute('aria-invalid', 'true');

  await from.fill('2024-01-01');
  await to.fill('2025-01-01');
  await expect(d.getByText('The period can be at most 366 days.')).toBeVisible();
  await expect(create).toBeDisabled();

  await to.fill('2024-12-31');
  await expect(d.locator('.cf-cr-range')).toHaveText('Jan 1 – Dec 31, 2024');
  await expect(create).toBeEnabled();
  const download = page.waitForEvent('download');
  await create.click();
  await finished(download);
  expect(mock.bodies).toEqual([{ period: 'CUSTOM', from: '2024-01-01', to: '2024-12-31', timeZone: timeZone(), includeFullAuditTrail: false }]);
});

test('busy: "Creating…", Cancel disabled, Esc does nothing; then it closes', async ({ page }) => {
  const mock = newMock({ status: 200, delayMs: 10_000 });
  await openReports(page, 'BUSINESS_ADMIN', mock);
  await button(page).click();
  const d = dialog(page);
  const download = page.waitForEvent('download');
  await d.getByRole('button', { name: 'Create PDF' }).click();
  await expect(d.getByRole('button', { name: 'Creating…' })).toBeDisabled();
  await expect(d.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  await expect(d.getByRole('button', { name: 'Last month' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(d).toBeVisible();
  // Clicking the dimmed page does nothing either.
  await page.mouse.click(10, 500);
  await expect(d).toBeVisible();
  await expect.poll(() => Boolean(mock.release)).toBe(true);
  mock.release!();
  await finished(download);
  await expect(d).toBeHidden();
});

for (const [status, words] of [
  [400, "This period can't be used. Choose another period."],
  [403, "You don't have access to create this report."],
  [500, "The report couldn't be created. Try again. If it keeps failing, tell your IT Administrator."],
] as const) {
  test(`error ${status}: shown inside the dialog, which stays open and can try again`, async ({ page }) => {
    const mock = newMock({ status });
    await openReports(page, 'BUSINESS_ADMIN', mock);
    await button(page).click();
    const d = dialog(page);
    await d.getByRole('button', { name: 'Create PDF' }).click();
    await expect(d.locator('.cf-notice[data-tone="danger"]')).toHaveText(words);
    await expect(d).toBeVisible();
    await expect(d.getByRole('button', { name: 'Create PDF' })).toBeEnabled();
    await expect(page.locator('.cf-toast')).toHaveCount(0);
    mock.answer = { status: 200 };
    const download = page.waitForEvent('download');
    await d.getByRole('button', { name: 'Create PDF' }).click();
    await finished(download);
    await expect(d).toBeHidden();
  });
}

test('focus: moves in on open, Tab and Shift+Tab stay inside, Esc and Cancel close and return focus; the dimmed page does nothing', async ({ page }) => {
  await openAudit(page, 'TENANT_IT_ADMIN', newMock());
  await button(page).click();
  const d = dialog(page);
  await expect(d.getByRole('button', { name: 'Last quarter' })).toBeFocused();
  const inside = () => page.evaluate(() => Boolean(document.activeElement?.closest('[role="dialog"]')));
  for (let i = 0; i < 12; i += 1) { await page.keyboard.press('Tab'); expect(await inside()).toBe(true); }
  for (let i = 0; i < 12; i += 1) { await page.keyboard.press('Shift+Tab'); expect(await inside()).toBe(true); }
  // From the first control, Shift+Tab goes to the last (Create PDF).
  await d.getByRole('button', { name: 'Last month' }).focus();
  await page.keyboard.press('Shift+Tab');
  await expect(d.getByRole('button', { name: 'Create PDF' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(d.getByRole('button', { name: 'Last month' })).toBeFocused();

  await page.mouse.click(10, 600);
  await expect(d).toBeVisible();
  expect(await inside(), 'a click on the dimmed page keeps focus in the dialog').toBe(true);

  await page.keyboard.press('Escape');
  await expect(d).toBeHidden();
  await expect(button(page)).toBeFocused();

  await button(page).click();
  await dialog(page).getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog(page)).toBeHidden();
  await expect(button(page)).toBeFocused();
});
