import { test, expect, type Locator, type Page, type Route } from '@playwright/test';
import { crc32, deflateSync } from 'node:zlib';
import { capabilitiesFor } from '../e2e-oidc/policyCapabilities';

/**
 * UI task U021: the tablet-friendly screens work by touch in WebKit (Safari's engine) with iPad settings, at the five
 * sizes in playwright.tablet.config.ts. Cases → Approve and Review → Redact are done by tap only; every control a person
 * taps on those screens is at least 44 × 44 px; nothing scrolls sideways.
 *
 * Same harness as e2e-oidc: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 * Run: npm run test:tablet.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c5c5c5c5-0000-4000-8000-00000000000c', name: 'Tablet Entity', slug: 'tablet-entity', roles: ['DOCUMENT_SUPERVISOR'] };
const CAPS = capabilitiesFor(...ENTITY.roles);

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'tablet-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

/** WebKit cannot fulfil a mocked request with a redirect, so the sign-in page sends the browser back with a script. */
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
      back.searchParams.set('code', 'tablet-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<script>location.replace(${JSON.stringify(back.toString())})</script>` });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'tablet-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

function caseFor(loan: string, status: string, n: string, name: string) {
  return {
    packageId: `c5c5c5c5-0000-4000-8000-00000000${n}`, tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status,
    documentCount: 1, createdAt: '2026-11-18T12:00:00Z', updatedAt: '2026-11-18T12:00:00Z', documents: [],
    businessIdentifiers: [
      { key: 'loan_number', label: 'Loan number', value: loan, primary: true, searchable: true, displayable: true, displayOrder: 1 },
      { key: 'borrower_name', label: 'Borrower name', value: name, primary: false, searchable: true, displayable: true, displayOrder: 2 },
    ],
  };
}
const REVIEW_CASE = caseFor('LN-3001', 'PENDING_REVIEW', '0001', 'Maria Alvarez');
const APPROVAL_CASE = caseFor('LN-3002', 'PENDING_FINAL_APPROVAL', '0002', 'James Okafor');
const STOPPED_CASE = caseFor('LN-3003', 'FAILED', '0003', 'Priya Natarajan');
const CASES = [REVIEW_CASE, APPROVAL_CASE, STOPPED_CASE];

const finding = (id: string, type: string, text: string) => ({
  findingId: id, packageId: REVIEW_CASE.packageId, documentId: 'doc-1', fileName: 'Closing_Disclosure.pdf', entityType: type, reviewTaskId: 't1',
  confidence: 0.62, pageNumber: 1, hasBoundingBox: true, boundingBox: { left: 0.2, top: 0.3, width: 0.2, height: 0.03 },
  reason: 'LOW_CONFIDENCE', foundText: { text, before: 'Borrower SSN: ', after: '' },
});

/** A blank page the size the backend renders a US Letter page at (144 DPI): 1224 × 1584, 8-bit grey. */
function blankPagePng(width = 1224, height = 1584): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([length, body, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = 0;
  const rows = Buffer.alloc((width + 1) * height, 0xff);
  for (let y = 0; y < height; y += 1) rows[y * (width + 1)] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const PAGE_PNG = blankPagePng();

type Calls = { approvals: string[]; confirmed: string[] };

/** The Reports headline numbers (five tiles); every other report section is empty. */
function reports() {
  const zeroes = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, 0]));
  return {
    generatedAt: '2026-11-18T20:00:00Z',
    period: { from: '2026-10-19T00:00:00Z', to: '2026-11-18T00:00:00Z', previousFrom: '2026-09-19T00:00:00Z', previousTo: '2026-10-19T00:00:00Z', source: 'ALL', outcome: 'ALL' },
    executive: {
      ...zeroes(['previousBatches', 'completed', 'previousCompleted', 'previousDelivered', 'periodIntakeDelivered', 'previousPeriodIntakeDelivered', 'completionRate', 'periodIntakeDeliveryRate']),
      batches: 512, documents: 1873, pages: 14220, delivered: 463, deliveryRate: 0.9, medianDeliveryHours: 2.4, p95DeliveryHours: 9.8,
      straightThroughRate: 0.68, reviewRate: 0.29, currentBacklog: 23, currentAttention: 4, oldestBacklogHours: 30.5,
      trend: [{ bucket: '2026-11-17T00:00:00Z', received: 12, delivered: 10 }, { bucket: '2026-11-18T00:00:00Z', received: 14, delivered: 11 }],
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
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, deliveryWithinTargetRate: 91, deliveryEligible: 463, deliveryBreaches: 42,
      straightThroughTargetPercent: 70, straightThroughActualPercent: 68,
      reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, reviewWithinTargetRate: 83, reviewEligible: 148, reviewBreaches: 25,
    },
    dimensions: [],
  };
}

async function mockApi(page: Page, calls: Calls, roles: string[] = ENTITY.roles) {
  let items = [finding('f1', 'SSN', '412-55-0193'), finding('f2', 'SSN', '518-22-7741'), finding('f3', 'SSN', '623-11-0042'), finding('f4', 'DATE_OF_BIRTH', '03/14/1979')];
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'tablet.user', roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC',
        capabilities: roles === ENTITY.roles ? CAPS : capabilitiesFor(...roles) });
    }
    if (url.pathname === '/api/v1/operations/reports') return json(reports());
    if (url.pathname === '/api/v1/packages') return json(CASES);
    const one = CASES.find((c) => url.pathname === `/api/v1/packages/${c.packageId}`);
    if (one) return json(one);
    if (url.pathname.endsWith('/approve-delivery') && request.method() === 'POST') {
      calls.approvals.push(url.pathname);
      return json({ packageId: APPROVAL_CASE.packageId, status: 'COMPLETED' });
    }
    if (url.pathname.includes('/lifecycle')) {
      return json({ packageId: '', successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: false, finalApprovalRequired: true, finalApprovalGranted: false });
    }
    const decision = url.pathname.match(/^\/api\/v1\/review-items\/([^/]+)\/(confirm|dismiss)$/);
    if (decision && request.method() === 'POST') {
      if (decision[2] === 'confirm') calls.confirmed.push(decision[1]);
      items = items.filter((i) => i.findingId !== decision[1]);
      return json({ findingId: decision[1], packageId: REVIEW_CASE.packageId, status: decision[2] === 'confirm' ? 'CONFIRMED' : 'DISMISSED', processingResumed: false });
    }
    if (url.pathname.endsWith('/page-preview')) {
      return route.fulfill({ status: 200, contentType: 'image/png', headers: { 'X-Review-Page-Number': '1' }, body: PAGE_PNG });
    }
    if (url.pathname === '/api/v1/review-items') return json({ totalItems: items.length, items, autoRedactThreshold: 0.85 });
    if (url.pathname.endsWith('/manual-fallback/eligibility')) return json({ eligible: true, reason: '' });
    if (url.pathname.endsWith('/manual-fallback') || url.pathname.endsWith('/manual-fallback/start')) {
      return json({ packageId: STOPPED_CASE.packageId, status: 'MANUAL_FALLBACK', documents: [{ documentId: 'doc-1', fileName: 'Title_Commitment.pdf', pageCount: 2 }],
        regions: [], enabledEntityTypes: ['SSN'] });
    }
    return json({});
  });
}

async function signIn(page: Page, calls: Calls = { approvals: [], confirmed: [] }) {
  await mockIdentityProvider(page);
  await mockApi(page, calls);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().tap();
  await page.locator('li.cf-case-row').first().waitFor();
}

const row = (page: Page, loan: string) => page.locator('li.cf-case-row').filter({ hasText: loan });

async function expectTappable(target: Locator) {
  const box = await target.boundingBox();
  expect(box, 'the control is on screen').not.toBeNull();
  expect(box!.width, 'at least 44 px wide').toBeGreaterThanOrEqual(44);
  expect(box!.height, 'at least 44 px high').toBeGreaterThanOrEqual(44);
}

/** Every visible control in the top bar and the main area, as "name WxH" for those under 44 px. */
async function smallControls(page: Page) {
  return page.evaluate(() => {
    const out: string[] = [];
    for (const el of document.querySelectorAll('header button, header a[href], main button, main a[href], main summary, main select, main input:not([type=hidden]):not(.cf-sr)')) {
      // A field inside its label is tapped anywhere on the label.
      const b = (el.tagName === 'INPUT' && el.closest('label') ? el.closest('label')! : el).getBoundingClientRect();
      if (b.width === 0 || b.height === 0 || getComputedStyle(el).visibility === 'hidden') continue;
      if (b.width < 44 || b.height < 44) out.push(`${(el.textContent || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 30)} ${Math.round(b.width)}x${Math.round(b.height)}`);
    }
    return out;
  });
}
const sideways = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

test('the device is a touch device: coarse pointer, no hover, IBM Plex loaded', async ({ page }) => {
  await signIn(page);
  const facts = await page.evaluate(async () => {
    await document.fonts.ready;
    return { coarse: matchMedia('(pointer: coarse)').matches, hoverNone: matchMedia('(hover: none)').matches, plex: document.fonts.check('14px "IBM Plex Sans"') };
  });
  expect(facts).toEqual({ coarse: true, hoverNone: true, plex: true });
});

test('Cases: identifier, next step, stage and action stay visible; every control is 44 px; nothing scrolls sideways', async ({ page }) => {
  await signIn(page);
  const approval = row(page, 'LN-3002');
  await expect(approval.locator('.cf-case-primary')).toBeVisible();
  await expect(approval.locator('.cf-case-next')).toHaveText('Redaction done, ready to approve');
  await expect(approval.locator('.cf-case-next')).toBeVisible();
  await expect(approval.locator('.cf-stage')).toBeVisible();
  await expect(approval.getByRole('button', { name: 'Approve LN-3002' })).toBeVisible();
  expect(await smallControls(page)).toEqual([]);
  expect(await sideways(page)).toBe(0);
});

test('Cases → Approve by tap: the row button asks, the dialog approves', async ({ page }) => {
  const calls: Calls = { approvals: [], confirmed: [] };
  await signIn(page, calls);
  const approve = row(page, 'LN-3002').getByRole('button', { name: 'Approve LN-3002' });
  await expectTappable(approve);
  await approve.tap();
  const dialog = page.getByRole('dialog', { name: 'Release redacted documents?' });
  await expect(dialog).toBeVisible();
  const confirm = dialog.getByRole('button', { name: 'Approve delivery' });
  await expectTappable(confirm);
  await expectTappable(dialog.getByRole('button', { name: 'Cancel' }));
  await confirm.tap();
  await expect.poll(() => calls.approvals.length).toBe(1);
});

test('Case panel → Approve delivery by tap, with every panel control 44 px', async ({ page }) => {
  const calls: Calls = { approvals: [], confirmed: [] };
  await signIn(page, calls);
  await row(page, 'LN-3002').getByRole('link').tap();
  const panel = page.getByRole('complementary', { name: 'Case panel' });
  await expect(panel).toBeVisible();
  await expectTappable(panel.getByRole('button', { name: /close/i }).first());
  for (const summary of await panel.locator('summary').all()) if (await summary.isVisible()) await expectTappable(summary);
  const approve = panel.getByRole('button', { name: 'Approve delivery' });
  await expectTappable(approve);
  await approve.tap();
  // The panel asks in place, with the same question as the row's dialog.
  const ask = panel.getByRole('group', { name: 'Release redacted documents?' });
  await expect(ask).toBeVisible();
  const confirm = ask.getByRole('button', { name: 'Confirm' });
  await expectTappable(confirm);
  await expectTappable(ask.getByRole('button', { name: 'Cancel' }));
  expect(await sideways(page)).toBe(0);
  await confirm.tap();
  await expect.poll(() => calls.approvals.length).toBe(1);
});

test('Review → Redact by tap, then Redact all like this by tap', async ({ page }) => {
  const calls: Calls = { approvals: [], confirmed: [] };
  await signIn(page, calls);
  await page.getByRole('link', { name: /^Review/ }).first().tap();
  const decision = page.getByRole('complementary', { name: 'Decision' });
  await expect(decision).toContainText('412-55-0193');
  expect(await smallControls(page)).toEqual([]);
  expect(await sideways(page)).toBe(0);

  // Portrait up to 1024 px: the decision is under the page; wider or landscape: beside it. Never all three squeezed.
  const view = page.viewportSize()!;
  const viewer = await page.locator('.cf-viewer').boundingBox();
  const panel = await decision.boundingBox();
  if (view.width <= 1024 && view.height > view.width) expect(panel!.y).toBeGreaterThanOrEqual(viewer!.y + viewer!.height);
  else expect(panel!.x).toBeGreaterThanOrEqual(viewer!.x + viewer!.width);

  const redact = decision.getByRole('button', { name: /^Redact/ }).first();
  await expectTappable(redact);
  await redact.tap();
  await expect.poll(() => calls.confirmed).toEqual(['f1']);

  // Two more SSNs wait in the case: Redact all asks first, then redacts both.
  const all = decision.getByRole('button', { name: /Redact all 2 like this/ });
  await expectTappable(all);
  await all.tap();
  const dialog = page.getByRole('dialog', { name: 'Redact 2 Social Security Number findings?' });
  await expect(dialog).toBeVisible();
  const confirm = dialog.getByRole('button', { name: 'Redact', exact: true });
  await expectTappable(confirm);
  await confirm.tap();
  await expect.poll(() => [...calls.confirmed].sort()).toEqual(['f1', 'f2', 'f3']);
  await expect(decision).toContainText('Date of birth');
});

test('Review: the queue item, Keep visible and zoom are all tappable without hover', async ({ page }) => {
  await signIn(page);
  await page.getByRole('link', { name: /^Review/ }).first().tap();
  const queue = page.getByRole('navigation', { name: 'Findings queue' });
  const dob = queue.locator('.cf-queue-item').filter({ hasText: 'Date of birth' });
  await expectTappable(dob);
  await dob.tap();
  const decision = page.getByRole('complementary', { name: 'Decision' });
  await expect(decision).toContainText('03/14/1979');
  await expectTappable(decision.getByRole('button', { name: /^Keep visible/ }));
  for (const name of ['Zoom out', 'Zoom in', 'Fit']) await expectTappable(page.getByRole('button', { name, exact: true }));
});

test('Continue manually: the touch note shows at the top and nothing scrolls sideways', async ({ page }) => {
  await signIn(page);
  await page.evaluate((p) => { window.history.pushState({}, '', p); window.dispatchEvent(new PopStateEvent('popstate')); }, `/packages/${STOPPED_CASE.packageId}/manual-processing`);
  await expect(page.getByRole('heading', { name: 'Continue manually', level: 1 })).toBeVisible();
  await expect(page.getByText('Marking values by hand works best on a computer.')).toBeVisible();
  expect(await sideways(page)).toBe(0);
});

test('Safari: the account menu opens by tap, its items are 44 px, and it closes by tapping the button again', async ({ page }) => {
  await signIn(page);
  const button = page.locator('.cf-acct-button');
  await expectTappable(button);
  await button.tap();
  const menu = page.getByRole('menu', { name: 'Account' });
  await expect(menu).toBeVisible();
  for (const item of await menu.locator('[role^="menuitem"]').all()) if (await item.isVisible()) await expectTappable(item);
  await expect(menu.getByText('Sign out')).toBeVisible();
  await button.tap();
  await expect(menu).toBeHidden();
});

test('Safari: on New case, "choose files" opens the file picker and the picked PDF is listed; a dropdown can be chosen', async ({ page }) => {
  await signIn(page);
  // Registered after signIn so it takes precedence over the catch-all API mock.
  await page.route('**/api/v1/intake/identifier-definitions', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
    { key: 'loan_number', label: 'Loan number', required: true, primary: true, searchable: true, extractable: false, displayable: true, displayOrder: 1, validationType: 'ALPHANUMERIC', caseNormalization: 'UPPER', stripSeparators: false, allowedValues: [] },
    { key: 'property_state', label: 'Property state', required: false, primary: false, searchable: false, extractable: false, displayable: true, displayOrder: 2, validationType: 'ALLOW_LIST', caseNormalization: 'UPPER', stripSeparators: false, allowedValues: ['FL', 'GA', 'TX'] },
  ]) }));
  await page.getByRole('link', { name: 'New case' }).first().tap();
  await expect(page.getByRole('heading', { name: 'New case', level: 1 })).toBeVisible();

  const state = page.getByLabel(/^Property state/);
  await expectTappable(state);
  await state.selectOption('GA');
  await expect(state).toHaveValue('GA');

  const choose = page.getByRole('button', { name: 'choose files' });
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), choose.tap()]);
  expect(chooser.isMultiple()).toBe(true);
  await chooser.setFiles({ name: 'Closing_Disclosure.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n', 'latin1') });
  await expect(page.getByText('Closing_Disclosure.pdf')).toBeVisible();
  // The hit area of the underlined "choose files" is 44 px high, though the word keeps its look.
  const hit = await choose.evaluate((el) => { const r = el.getBoundingClientRect(); const a = getComputedStyle(el, '::after'); return r.height + parseFloat(a.top) * -1 + parseFloat(a.bottom) * -1; });
  expect(hit).toBeGreaterThanOrEqual(44);
  expect(await sideways(page)).toBe(0);
});

for (const [who, roles] of [['Report viewer', ['REPORT_VIEWER']], ['Business Administrator', ['BUSINESS_ADMIN']]] as const) {
  test(`Reports (${who}): the five headline tiles fill their rows; on a portrait tablet they sit 3 + 2`, async ({ page }, info) => {
    await mockIdentityProvider(page);
    await mockApi(page, { approvals: [], confirmed: [] }, [...roles]);
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().tap();
    await page.locator('main h1').first().waitFor();
    await page.evaluate(() => { window.history.pushState({}, '', '/reports'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    const stats = page.locator('dl.cf-rp-stats');
    await expect(stats.locator('.cf-rp-stat')).toHaveCount(5);
    const { rows, gaps } = await stats.evaluate((dl) => {
      const box = dl.getBoundingClientRect();
      const tops = new Map<number, DOMRect[]>();
      for (const el of dl.querySelectorAll(':scope > .cf-rp-stat')) {
        const r = el.getBoundingClientRect();
        const key = Math.round(r.top);
        tops.set(key, [...(tops.get(key) || []), r]);
      }
      const ordered = [...tops.entries()].sort((a, b) => a[0] - b[0]).map(([, rs]) => rs);
      // How far each row's last tile stops short of the box's right edge (0 when the row is filled).
      return { rows: ordered.map((rs) => rs.length), gaps: ordered.map((rs) => Math.round(box.right - Math.max(...rs.map((r) => r.right)))) };
    });
    for (const gap of gaps) expect(gap, 'every row of tiles reaches the right edge').toBeLessThanOrEqual(2);
    const { width, height } = info.project.use.viewport!;
    if (width <= 1024 && height > width) expect(rows).toEqual([3, 2]);
    expect(await sideways(page)).toBe(0);
  });
}

/** U022: sign in as `role` and open a page by its address, the way the Reports test above does. */
async function signInAs(page: Page, roles: string[], path: string, routes?: () => Promise<void>) {
  await mockIdentityProvider(page);
  await mockApi(page, { approvals: [], confirmed: [] }, roles);
  // Registered after the catch-all API mock, so they take precedence.
  if (routes) await routes();
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().tap();
  await page.locator('main h1').first().waitFor();
  await page.evaluate((p) => { window.history.pushState({}, '', p); window.dispatchEvent(new PopStateEvent('popstate')); }, path);
}

test('Reports (Business Administrator): the Compliance report dialog works by tap and creates a PDF', async ({ page }) => {
  const bodies: unknown[] = [];
  await signInAs(page, ['BUSINESS_ADMIN'], '/reports');
  // Registered after sign-in so it takes precedence over the catch-all API mock.
  await page.route('**/api/v1/compliance-reports', (route) => {
    bodies.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: 'application/pdf', body: Buffer.from('%PDF-1.7\n%%EOF\n', 'latin1'),
      headers: { 'Content-Disposition': 'attachment; filename="compliance-report-tablet-entity-2026-07-01-2026-09-30.pdf"' } });
  });
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  const open = page.getByRole('button', { name: 'Compliance report', exact: true });
  await expectTappable(open);
  await open.tap();
  const dialog = page.getByRole('dialog', { name: 'Compliance report' });
  await expect(dialog).toBeVisible();
  for (const name of ['Last month', 'Last quarter', 'Last year', 'Custom', 'Cancel', 'Create PDF']) {
    await expectTappable(dialog.getByRole('button', { name, exact: true }));
  }
  // Custom by tap: the two date fields are 44 px too, then back to Last month.
  await dialog.getByRole('button', { name: 'Custom' }).tap();
  for (const label of ['From', 'To']) {
    const box = await dialog.getByLabel(label).boundingBox();
    expect(box!.height, `${label} is at least 44 px high`).toBeGreaterThanOrEqual(44);
  }
  await dialog.getByRole('button', { name: 'Last month' }).tap();
  await expect(dialog.getByRole('button', { name: 'Last month' })).toHaveAttribute('aria-pressed', 'true');
  expect(await sideways(page)).toBe(0);

  const download = page.waitForEvent('download');
  await dialog.getByRole('button', { name: 'Create PDF' }).tap();
  const file = await download;
  expect(file.suggestedFilename()).toBe('compliance-report-tablet-entity-2026-07-01-2026-09-30.pdf');
  await file.delete();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('Compliance report downloaded.')).toBeVisible();
  expect(bodies).toHaveLength(1);
  expect(bodies[0]).toMatchObject({ period: 'LAST_MONTH', includeFullAuditTrail: false });
  expect(typeof (bodies[0] as { timeZone: unknown }).timeZone).toBe('string');
});

test('Admin › Audit trail (IT Administrator): the page and the dialog do not scroll sideways', async ({ page }) => {
  await signInAs(page, ['TENANT_IT_ADMIN'], '/admin/audit', async () => {
    await page.route('**/api/v1/audit-records**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
    await page.route('**/api/v1/integrations', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  });
  const open = page.getByRole('button', { name: 'Compliance report', exact: true });
  await expect(open).toBeVisible();
  expect(await sideways(page)).toBe(0);
  await open.tap();
  const dialog = page.getByRole('dialog', { name: 'Compliance report' });
  await expect(dialog.getByRole('checkbox', { name: /Include full audit trail/ })).toBeChecked();
  expect(await sideways(page)).toBe(0);
  await dialog.getByRole('button', { name: 'Cancel' }).tap();
  await expect(dialog).toBeHidden();
});

test('Admin › Redaction & retention (Business Administrator): the Sensitive data card, its preview and the confirm step do not scroll sideways', async ({ page }) => {
  const policy = { enabledEntityTypes: ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE'], autoRedactThreshold: 0.8, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true };
  const json = (body: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  await signInAs(page, ['BUSINESS_ADMIN'], '/admin/policy', async () => {
    await page.route('**/api/v1/business-admin/configuration', (route) => route.fulfill(json({
      identifiers: [], dashboardColumns: [], sensitiveDataPolicy: policy,
      lifecyclePolicy: { originalRetentionHours: 72, redactedCacheHours: 168, neverExportedHoldHours: 336, finalApprovalRequired: false, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 720 },
      documentIntelligence: { mode: 'OBSERVE', activeBundleVersion: 'b1', lastKnownGoodBundleVersion: 'b1', configurationVersion: 1, baselineDiscoveryRequired: false,
        availableBundles: [{ version: 'b1', status: 'PROMOTED', modelVersion: 'm', promptVersion: 'p', detectorVersion: 'd', ocrRulesVersion: 'o', profileVersion: 'none', validationRulesVersion: 'v', thresholdsVersion: 't', description: '', promotedAt: '2026-11-01T15:00:00Z' }] },
      reportingConfiguration: { dimensions: [], deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70, reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80 },
    })));
    await page.route('**/api/v1/business-admin/sensitive-data-policy/estimate**', (route) => route.fulfill(json({
      windowDays: 30, cases: 512, enoughData: true,
      current: { reviewMode: 'UNCERTAIN_ONLY', threshold: 0.8, preset: 'BALANCED', casesToPerson: 148, findingsToPerson: 340 },
      proposed: { reviewMode: 'UNCERTAIN_ONLY', threshold: 0.7, preset: 'LIGHT', casesToPerson: 96, findingsToPerson: 205 },
    })));
  });
  const card = page.getByRole('region', { name: 'Sensitive data' });
  await expect(card.getByRole('radio', { name: /^Balanced In use/ })).toBeChecked();
  expect(await sideways(page)).toBe(0);
  await card.getByText('Light', { exact: true }).tap();
  await expect(card.getByRole('status').locator('.cf-sp-change')).toHaveText(['▼ 52 fewer', '▼ 135 fewer']);
  await card.getByText('Fine-tune by hand').tap();
  await expect(card.getByLabel(/Auto-redact when confidence is at least/)).toBeVisible();
  expect(await sideways(page)).toBe(0);
  const save = card.getByRole('button', { name: 'Save Light' });
  await expectTappable(save);
  await save.tap();
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await expect(dialog).toBeVisible();
  for (const name of ['Keep Balanced', 'Save Light']) await expectTappable(dialog.getByRole('button', { name }));
  expect(await sideways(page)).toBe(0);
  await dialog.getByRole('button', { name: 'Keep Balanced' }).tap();
  await expect(dialog).toBeHidden();
  await expect(card.getByRole('radio', { name: /^Balanced In use/ })).toBeChecked();
});
