import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U012: Support list, one problem page, Continue manually (UI spec findings 9, 10, 30), one test per role rule.
 * Same harness as adminAudit.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c8c8c8c8-0000-4000-8000-00000000000e', name: 'Residential lending', slug: 'residential' };
const ROLES: Record<string, { roles: string[]; caps: string[] }> = {
  it: { roles: ['TENANT_IT_ADMIN'], caps: capabilitiesFor('TENANT_IT_ADMIN') },
  ops: { roles: ['PLATFORM_OPERATIONS_ADMIN'], caps: capabilitiesFor('PLATFORM_OPERATIONS_ADMIN') },
  processor: { roles: ['DOCUMENT_PROCESSOR'], caps: capabilitiesFor('DOCUMENT_PROCESSOR') },
  supervisor: { roles: ['DOCUMENT_SUPERVISOR'], caps: capabilitiesFor('DOCUMENT_SUPERVISOR') },
};

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'support-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
const minsAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

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
      back.searchParams.set('code', 'support-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'support-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const PKG = 'd4d4d4d4-0000-4000-8000-000000000001';
const DOC = 'd4d4d4d4-0000-4000-8000-0000000000d1';
const INC = 'e5e5e5e5-0000-4000-8000-000000000001';
const summary = (n: number, loan: string, reasonCode: string, stage: string, status: string, businessImpact: string, attemptCount: number, m: number) => ({
  incidentId: n === 1 ? INC : `e5e5e5e5-0000-4000-8000-00000000000${n}`, tenantId: ENTITY.tenantId, packageId: n === 1 ? PKG : `d4d4d4d4-0000-4000-8000-00000000000${n}`,
  documentId: DOC, primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: loan, stage, component: 'worker', reasonCode, status,
  businessOutcome: businessImpact === 'COMPLETED' ? 'COMPLETED' : null, businessContinuityUsed: businessImpact !== 'BLOCKED', retryable: true,
  attemptCount, correlationId: `c0ffee00-0000-4000-8000-00000000000${n}`, businessImpact, firstObservedAt: minsAgo(m + 20), lastObservedAt: minsAgo(m),
  resolvedAt: status === 'RESOLVED' ? minsAgo(m) : null,
});
const ITEMS = [
  summary(1, 'LN204860', 'OCR_TIMEOUT', 'OCR', 'OPEN', 'BLOCKED', 3, 180),
  summary(2, 'LN204864', 'DELIVERY_IO', 'DELIVERY', 'BUSINESS_CONTINUED', 'CONTINUED', 1, 185),
  summary(3, 'LN204867', 'AI_TIMEOUT', 'AI_ANALYSIS', 'RESOLVED', 'COMPLETED', 1, 60),
];
const DETAIL = {
  ...ITEMS[0], packageStatus: 'FAILED', failureCategory: 'DEPENDENCY_TIMEOUT', safeSummary: 'OCR did not complete within the allowed time.',
  artifactAvailability: 'RETAINED_UNTIL_POLICY_EXPIRY', technicalAttentionRetainedUntil: new Date(Date.now() + 2 * 864e5).toISOString(), artifactsPurgedAt: null,
  processingFingerprint: { bundle: '2026.11.1', ocr: 'tesseract-5.4' }, safeMetadata: { page: '14' },
  // Task 0038: the problem's document by position and page, never by file name.
  documentPosition: { position: 2, count: 3, page: 14 },
  events: [
    { eventType: 'FAILURE_RECORDED', attemptNumber: 1, scope: 'DOCUMENT', reasonCode: 'OCR_TIMEOUT', retryable: true, correlationId: 'corr-91', occurredAt: minsAgo(200), safeMetadata: {} },
    { eventType: 'FAILURE_RECORDED', attemptNumber: 3, scope: 'DOCUMENT', reasonCode: 'OCR_TIMEOUT', retryable: true, correlationId: 'corr-93', occurredAt: minsAgo(180), safeMetadata: {} },
    { eventType: 'PROCESSING_RECOVERY_EXHAUSTED', correlationId: 'corr-93', occurredAt: minsAgo(179), safeMetadata: {} },
  ],
};
const STOPPED_CASE = {
  packageId: PKG, tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status: 'FAILED', documentCount: 1,
  createdAt: '2026-11-17T12:00:00Z', updatedAt: '2026-11-17T12:00:00Z', documents: [{ documentId: DOC, fileName: 'Title_Commitment.pdf' }],
  businessIdentifiers: [
    { key: 'loan_number', label: 'Loan number', value: 'LN204860', primary: true, searchable: true, displayable: true, displayOrder: 1 },
    { key: 'borrower', label: 'Borrower name', value: 'Priya Natarajan', primary: false, searchable: true, displayable: true, displayOrder: 2 },
  ],
};

interface Calls { searches: URLSearchParams[]; posts: Array<{ path: string; body: unknown }> }

async function openAs(page: Page, role: keyof typeof ROLES): Promise<Calls> {
  const calls: Calls = { searches: [], posts: [] };
  const regions: Array<Record<string, unknown>> = [];
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (req.method() === 'POST' || req.method() === 'DELETE') calls.posts.push({ path, body: req.postDataJSON?.() ?? null });
    if (path === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: ROLES[role].roles }]);
    if (path === '/api/v1/me') return json({ username: `${role}@example.test`, roles: ROLES[role].roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: ROLES[role].caps });
    if (path === '/api/v1/integrations' || path === '/api/v1/derived-deliveries/attention') return json([]);
    // Platform Operations lands on Admin › Platform operations first; this test does not cover that screen.
    if (path.startsWith('/api/v1/platform-admin/') || path.startsWith('/api/v1/platform/')) return json({ message: 'not in this test' }, 503);
    if (path === `/api/v1/packages/${PKG}/manual-fallback/eligibility`) return json({ eligible: true, reason: '' });
    if (path === '/api/v1/support/incidents') {
      calls.searches.push(url.searchParams);
      const q = url.searchParams;
      const items = ITEMS.filter((i) => (!q.get('businessIdentifier') || i.primaryIdentifierValue.toLowerCase().includes(q.get('businessIdentifier')!.toLowerCase()))
        && (!q.get('incidentId') || i.incidentId === q.get('incidentId')) && (!q.get('stage') || i.stage === q.get('stage'))
        && (!q.get('reasonCode') || i.reasonCode === q.get('reasonCode')));
      return json({ items, total: items.length, page: 0, size: Number(q.get('size') || 25) });
    }
    if (path === `/api/v1/support/incidents/${INC}`) return json(DETAIL);
    if (path === `/api/v1/support/incidents/${INC}/actions`) return json({ incidentId: INC, packageId: PKG, action: 'X', status: 'ACCEPTED' }, 202);
    if (path === `/api/v1/technical-exceptions/${PKG}`) {
      return json({ packageId: PKG, documentId: DOC, status: 'ATTENTION_REQUIRED', failureCategory: 'DEPENDENCY_TIMEOUT', failedStage: 'OCR', retryable: true,
        failedAt: minsAgo(180), correlationId: 'corr-93', originalRetainedUntil: DETAIL.technicalAttentionRetainedUntil, explanation: 'OCR did not complete.',
        incidentId: INC, incidentStatus: 'OPEN', businessContinuityUsed: false, businessOutcome: null, processingFingerprint: {}, incidentEvents: [], operatorDiagnostics: null });
    }
    if (path === '/api/v1/packages') return json([STOPPED_CASE]);
    if (path === `/api/v1/packages/${PKG}`) return json(STOPPED_CASE);
    if (path.startsWith('/api/v1/review-items')) return json({ totalItems: 0, items: [] });
    if (path.includes('/lifecycle')) return json({ packageId: PKG, successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: false, finalApprovalRequired: true, finalApprovalGranted: false });
    if (path === `/api/v1/packages/${PKG}/manual-fallback/start`) return json({ packageId: PKG, status: 'MANUAL_FALLBACK', documents: [], regions: [], enabledEntityTypes: [] });
    if (path === `/api/v1/packages/${PKG}/manual-fallback`) {
      return json({ packageId: PKG, status: 'MANUAL_FALLBACK', documents: [{ documentId: DOC, fileName: 'Title_Commitment.pdf', pageCount: 3 }], regions, enabledEntityTypes: ['US_SSN', 'DATE_OF_BIRTH'] });
    }
    if (path.endsWith('/preview')) return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    if (path === `/api/v1/packages/${PKG}/manual-fallback/regions` && req.method() === 'POST') {
      const body = req.postDataJSON() as Record<string, unknown>;
      const region = { findingId: `m-${regions.length + 1}`, documentId: body.documentId, pageNumber: body.pageNumber, entityType: body.entityType, ...(body.boundingBox as object) };
      regions.push(region);
      return json(region);
    }
    if (path === `/api/v1/packages/${PKG}/manual-fallback/submit`) return json({ packageId: PKG, status: 'SUBMITTED' });
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('navigation', { name: 'Workspace' })).toBeVisible();
  return calls;
}

const pageErrors: string[] = [];
test.beforeEach(({ page }) => { pageErrors.length = 0; page.on('pageerror', (e) => pageErrors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); }); });
test.afterEach(() => { expect(pageErrors.filter((e) => !/Failed to load resource|returned|failed to render/.test(e))).toEqual([]); });

/** Text a person sees, without the Technical details, the More filters and the option lists. */
const visibleText = (page: Page) => page.locator('#cf-main').evaluate((e) => {
  const c = e.cloneNode(true) as HTMLElement;
  c.querySelectorAll('details, option, select').forEach((d) => d.remove());
  return c.innerText;
});

async function openSupport(page: Page) {
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Support' }).click();
  await expect(page.getByRole('heading', { name: 'Support' })).toBeVisible();
}

test('IT Administrator: Support rows in plain words, Open / Resolved / All, one search box and More filters', async ({ page }) => {
  const calls = await openAs(page, 'it');
  await openSupport(page);
  await expect(page.getByText('Processing problems in Residential lending')).toBeVisible();
  await expect(page.getByText('1 is blocking a case')).toBeVisible();
  const chips = page.getByRole('radiogroup', { name: 'Show' });
  await expect(chips.getByRole('radio', { name: /Open 2/ })).toHaveAttribute('aria-checked', 'true');
  await expect(chips.getByRole('radio', { name: /Resolved 1/ })).toBeVisible();
  await expect(chips.getByRole('radio', { name: /All 3/ })).toBeVisible();
  const list = page.getByRole('list', { name: 'Problems' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  const first = list.getByRole('listitem').filter({ hasText: 'LN204860' });
  // Task 0025: the reason reads as the LV code's title and the code.
  await expect(first).toContainText('Text reading timed out · LV-PRC-004 · tried 3 times');
  await expect(first).toContainText('Case stopped');
  await expect(first).toContainText('Open');
  await expect(first).toContainText('3h ago');
  await expect(list.getByRole('listitem').filter({ hasText: 'LN204864' })).toContainText("Destination can't be reached · LV-DLV-001 · case continued manually");
  await expect(list.getByRole('listitem').filter({ hasText: 'LN204864' })).toContainText('Watching');
  expect(await visibleText(page)).not.toMatch(/[A-Z]{2,}_[A-Z]/);
  expect(calls.searches[0].get('size')).toBe('100');

  await chips.getByRole('radio', { name: /Resolved/ }).click();
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await expect(list).toContainText('Case finished');

  await chips.getByRole('radio', { name: /All/ }).click();
  const search = page.getByPlaceholder('Loan number or incident number');
  await search.fill('LN204864');
  await search.press('Enter');
  await expect.poll(() => calls.searches.at(-1)?.get('businessIdentifier')).toBe('LN204864');
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await search.fill(INC);
  await search.press('Enter');
  await expect.poll(() => calls.searches.at(-1)?.get('incidentId')).toBe(INC);
  await search.fill('');

  const more = page.locator('details.cf-sp-more');
  expect(await more.getAttribute('open')).toBeNull();
  await more.getByText('More filters').click();
  await expect(more.getByLabel(/^Organization ID/)).toHaveCount(0);
  await more.getByLabel(/^Where it stopped/).selectOption({ label: 'Text recognition' });
  await more.getByRole('button', { name: 'Apply filters' }).click();
  await expect.poll(() => calls.searches.at(-1)?.get('stage')).toBe('OCR');
  await expect(more.getByText('More filters (1)')).toBeVisible();

  // Task 0025: the Reason filter lists LV codes; the server is still asked by reason code, once per reason code of the LV code.
  await more.getByLabel(/^Where it stopped/).selectOption({ label: 'Any stage' });
  await more.getByLabel(/^Reason/).selectOption({ label: 'Text reading timed out · LV-PRC-004' });
  await more.getByRole('button', { name: 'Apply filters' }).click();
  await expect.poll(() => calls.searches.at(-1)?.get('reasonCode')).toBe('OCR_TIMEOUT');
  await expect(list.getByRole('listitem')).toHaveCount(1);
  const before = calls.searches.length;
  await more.getByLabel(/^Reason/).selectOption({ label: 'Redacted copy failed the safety check · LV-RED-002' });
  await more.getByRole('button', { name: 'Apply filters' }).click();
  await expect.poll(() => calls.searches.length - before).toBe(8);
  expect(new Set(calls.searches.slice(before).map((s) => s.get('reasonCode')))).toEqual(new Set([
    'REDACTION_SOURCE_PAGES_UNKNOWN', 'REDACTION_PAGE_COUNT_MISMATCH', 'REDACTION_TARGETS_NOT_APPLIED', 'REDACTION_OUTPUT_EMPTY',
    'REDACTION_TEXT_LAYER_REMAINS', 'REDACTION_ANNOTATIONS_REMAIN', 'REDACTION_REGION_NOT_COVERED', 'REDACTION_OUTPUT_UNPARSABLE',
  ]));
  await expect(page.getByText('Nothing matches')).toBeVisible();
});

test('IT Administrator: one problem page with Retry and Keep; every code under Technical details', async ({ page }) => {
  const calls = await openAs(page, 'it');
  await openSupport(page);
  await page.getByRole('list', { name: 'Problems' }).getByRole('link').filter({ hasText: 'LN204860' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Text reading timed out · LV-PRC-004' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Support›LN204860');
  await expect(page.locator('.cf-pb-title')).toContainText('Case stopped');
  const what = page.getByRole('region', { name: 'What happened' });
  await expect(what).toContainText('Reading text from a page took too long. It stopped at text recognition, in document 2 of 3, page 14. LexaVault tried 3 times');
  await expect(page.locator('main')).not.toContainText('Title_Commitment'); // task 0038: never the file name
  if (process.env.P0038_PROOF) await page.screenshot({ path: '../../docs/work/0038-proof/support-document-position.png', fullPage: true });
  await expect(page.getByRole('region', { name: 'What it means for the case' })).toContainText('The case is stopped. Nothing was sent. The original is kept until');
  const timeline = page.getByRole('region', { name: 'Timeline' });
  await expect(timeline).toContainText('Try 1 failed: text reading timed out');
  await expect(timeline).toContainText('No tries left; case stopped and support told');
  expect(await visibleText(page)).not.toMatch(/[A-Z]{2,}_[A-Z]|corr-9/);

  const tech = page.locator('details.cf-tech');
  await tech.locator('summary').click();
  for (const v of [INC, 'OCR_TIMEOUT', 'c0ffee00-0000-4000-8000-000000000001', 'tesseract-5.4', ENTITY.tenantId, 'FAILURE_RECORDED']) await expect(tech).toContainText(v);

  const doCard = page.getByRole('region', { name: 'What you can do' });
  await expect(doCard).toContainText('can also finish it with “Continue manually”');
  await doCard.getByRole('button', { name: 'Keep the original 7 more days' }).click();
  await expect.poll(() => calls.posts.at(-1)?.body).toEqual({ action: 'PRESERVE_FOR_INVESTIGATION', holdDays: 7 });

  await doCard.getByRole('button', { name: 'Retry processing' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('LN204860');
  await dialog.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect.poll(() => calls.posts.at(-1)).toEqual({ path: `/api/v1/support/incidents/${INC}/actions`, body: { action: 'REPROCESS' } });

  await doCard.getByText('More actions').click();
  await doCard.getByRole('button', { name: 'Close without processing again' }).click();
  await expect(page.getByRole('dialog')).toContainText('the case stays stopped');
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
  expect(calls.posts.filter((p) => JSON.stringify(p.body).includes('RESOLVE_NO_REPROCESS'))).toHaveLength(0);
});

test('IT Administrator: a stopped case’s problem link opens the same page', async ({ page }) => {
  await openAs(page, 'it');
  await page.evaluate((p) => { window.history.pushState({}, '', `/technical-exceptions/${p}`); window.dispatchEvent(new PopStateEvent('popstate')); }, PKG);
  await expect(page.getByRole('heading', { level: 1, name: 'Text reading timed out · LV-PRC-004' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'What you can do' }).getByRole('button', { name: 'Retry processing' })).toBeVisible();
});

test('Platform Operations: Support and the problem page, but no Retry and no keep actions', async ({ page }) => {
  const calls = await openAs(page, 'ops');
  await openSupport(page);
  const more = page.locator('details.cf-sp-more');
  await more.getByText('More filters').click();
  await expect(more.getByLabel(/^Organization ID/)).toBeVisible();
  await page.getByRole('list', { name: 'Problems' }).getByRole('link').filter({ hasText: 'LN204860' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Text reading timed out · LV-PRC-004' })).toBeVisible();
  const doCard = page.getByRole('region', { name: 'What you can do' });
  await expect(doCard.getByRole('button')).toHaveCount(0);
  await expect(doCard).toContainText('Retry and keeping the original are for IT Administrators');
  expect(calls.posts).toHaveLength(0);
});

async function continueManually(page: Page, role: 'processor' | 'supervisor') {
  const calls = await openAs(page, role);
  await expect(page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Support' })).toHaveCount(0);
  const row = page.locator('li.cf-case-row').filter({ hasText: 'LN204860' });
  await row.getByRole('button', { name: /Continue manually/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Continue manually' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Continue manually' })).toBeVisible();
  expect(calls.posts.some((p) => p.path.endsWith('/manual-fallback/start'))).toBe(true);
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Cases›LN204860 · Priya Natarajan');
  await expect(page.getByText('Automatic processing stopped. Mark every sensitive value by hand.')).toBeVisible();
  await expect(page.getByText('3 pages · 0 marked')).toBeVisible();
  const type = page.getByLabel('What is it?');
  await expect(type.locator('option')).toHaveText(['Social Security Number', 'Date of birth']);

  const finish = page.getByRole('button', { name: 'Finish and send to approval' });
  await expect(finish).toBeDisabled();
  const surface = page.locator('.cf-viewer .cf-page');
  await expect(surface.locator('img')).toBeVisible();
  const box = (await surface.boundingBox())!;
  await page.mouse.move(box.x + box.width * 0.2, box.y + box.height * 0.2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.3, { steps: 4 });
  await page.mouse.up();
  await page.getByRole('button', { name: 'Add mark' }).click();
  await expect.poll(() => calls.posts.find((p) => p.path.endsWith('/manual-fallback/regions'))?.body).toMatchObject({ documentId: DOC, pageNumber: 1, entityType: 'US_SSN' });
  await expect(page.getByText('Marked on this page (1)')).toBeVisible();
  await expect(page.getByText('3 pages · 1 marked')).toBeVisible();

  const check = page.getByLabel('I checked every page and marked all sensitive values.');
  const size = (await check.boundingBox())!;
  expect(size.width).toBeLessThan(24);
  await check.check();
  await finish.click();
  await expect.poll(() => calls.posts.find((p) => p.path.endsWith('/manual-fallback/submit'))?.body).toEqual({ reviewAttested: true });
  await expect(page).toHaveURL(new RegExp(`/case/${PKG}$`));
}

test('Document Processor: Continue manually from the case, mark a value, finish and send to approval', async ({ page }) => {
  await continueManually(page, 'processor');
});

test('Document Supervisor: Continue manually works the same way; Support is not offered', async ({ page }) => {
  await continueManually(page, 'supervisor');
  await page.evaluate(() => { window.history.pushState({}, '', '/support/incidents'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await expect(page.getByRole('heading', { name: 'Support' })).toHaveCount(0);
});
