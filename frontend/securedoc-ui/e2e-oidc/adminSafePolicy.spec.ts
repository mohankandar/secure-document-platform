import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U023: Admin › Redaction & retention › Sensitive data with presets, the estimate preview and the confirm
 * step when fewer findings would go to a person. Same harness as adminBusiness.spec.ts: the real app on the Vite dev
 * server (OIDC mode), the identity provider and the API mocked, including GET …/sensitive-data-policy/estimate.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c7c7c7c7-0000-4000-8000-00000000000c', name: 'Residential lending', slug: 'residential-lending' };
const CAPS = capabilitiesFor('BUSINESS_ADMIN');

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'admin-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

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
      back.searchParams.set('code', 'admin-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'admin-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

type Policy = { enabledEntityTypes: string[]; autoRedactThreshold: number; reviewMode: 'UNCERTAIN_ONLY' | 'REVIEW_ALL'; maskSsnLastFour: boolean; enabledPacks?: string[]; keepLastFourKinds?: string[]; lockedItemsOn?: boolean };

/** Task 0062: as the server sends it, shortened. Tests without it run the screen against an older server. */
const KIND = (key: string, locked = false, lastFourAllowed = false) => ({ key, name: key, detectionMethod: 'PATTERN', defaultStyle: 'REMOVE_FULLY', locked, lastFourAllowed });
const CATALOG = {
  kinds: [KIND('SSN', false, true), KIND('DATE_OF_BIRTH'), KIND('CREDIT_CARD_NUMBER', true), KIND('CREDIT_SCORE'), KIND('ITIN', false, true), KIND('EIN', false, true), KIND('CVV', true), KIND('PIN', true)],
  packs: [
    { key: 'PCI_DSS', name: 'PCI DSS', covers: 'Payment card data.', notCovered: 'Cardholder names are not found.', kinds: ['CREDIT_CARD_NUMBER', 'CVV', 'PIN'] },
    { key: 'FRCP_5_2', name: 'FRCP 5.2 (court filings)', covers: 'Social Security and taxpayer numbers.', notCovered: 'Names of minors are not found.', kinds: ['SSN', 'ITIN', 'EIN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER'] },
  ],
};
const BALANCED: Policy = { enabledEntityTypes: ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE'], autoRedactThreshold: 0.8, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true };

function configuration(policy: Policy, catalog?: typeof CATALOG) {
  return {
    identifiers: [], dashboardColumns: [],
    sensitiveDataPolicy: policy,
    redactionCatalog: catalog,
    lifecyclePolicy: { originalRetentionHours: 72, redactedCacheHours: 168, neverExportedHoldHours: 336, finalApprovalRequired: false, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 720 },
    documentIntelligence: {
      mode: 'OBSERVE', activeBundleVersion: '2026.11.1', lastKnownGoodBundleVersion: '2026.11.1', configurationVersion: 4, baselineDiscoveryRequired: false,
      availableBundles: [{ version: '2026.11.1', status: 'PROMOTED', modelVersion: 'qwen3.5:9b', promptVersion: 'p7', detectorVersion: 'd3', ocrRulesVersion: 'o2', profileVersion: 'none',
        validationRulesVersion: 'v5', thresholdsVersion: 't4', description: 'Improved reading.', promotedAt: '2026-11-01T15:00:00Z' }],
    },
    reportingConfiguration: { dimensions: [], deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70, reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, minutesPerPage: 1 },
  };
}

/** Cases and findings a person would check under each proposal (the saved policy is always 148 / 340). */
const COUNTS: Record<string, [number, number]> = {
  'REVIEW_ALL': [512, 2410],
  'UNCERTAIN_ONLY:0.80': [148, 340],
  'UNCERTAIN_ONLY:0.70': [96, 205],
  'UNCERTAIN_ONLY:0.77': [130, 300],
  'UNCERTAIN_ONLY:0.85': [170, 410],
};

type Estimate = (q: URLSearchParams) => { status?: number; delayMs?: number; body?: unknown } | undefined;
interface Harness { writes: Policy[]; estimates: string[] }

function presetOf(mode: string, t?: string) {
  if (mode === 'REVIEW_ALL') return 'STRICT';
  return t === '0.80' ? 'BALANCED' : t === '0.70' ? 'LIGHT' : 'CUSTOM';
}

function defaultEstimate(q: URLSearchParams, cases = 512) {
  const mode = q.get('reviewMode')!;
  const t = q.get('threshold') ?? undefined;
  const [c, f] = COUNTS[mode === 'REVIEW_ALL' ? mode : `${mode}:${t}`] ?? [148, 340];
  const enough = cases >= 20;
  return {
    windowDays: 30, cases, enoughData: enough,
    current: { reviewMode: 'UNCERTAIN_ONLY', threshold: 0.8, preset: 'BALANCED', ...(enough ? { casesToPerson: 148, findingsToPerson: 340 } : {}) },
    proposed: { reviewMode: mode, threshold: t ? Number(t) : null, preset: presetOf(mode, t), ...(enough ? { casesToPerson: c, findingsToPerson: f } : {}) },
  };
}

async function openPolicy(page: Page, opts: { catalog?: typeof CATALOG; policy?: Policy; estimate?: Estimate } = {}): Promise<Harness> {
  const h: Harness = { writes: [], estimates: [] };
  let policy = opts.policy ?? BALANCED;
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: ['BUSINESS_ADMIN'] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'admin.user@example.test', roles: ['BUSINESS_ADMIN'], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: CAPS });
    }
    if (url.pathname === '/api/v1/business-admin/configuration') return json(configuration(policy, opts.catalog));
    if (url.pathname === '/api/v1/business-admin/sensitive-data-policy/estimate') {
      h.estimates.push(url.search);
      const custom = opts.estimate?.(url.searchParams);
      if (custom?.delayMs) await new Promise((r) => setTimeout(r, custom.delayMs));
      if (custom?.status && custom.status >= 400) return json({ message: 'failed' }, custom.status).catch(() => undefined);
      return json(custom?.body ?? defaultEstimate(url.searchParams)).catch(() => undefined);
    }
    if (request.method() === 'PUT' && url.pathname === '/api/v1/business-admin/sensitive-data-policy') {
      const body = JSON.parse(request.postData() || '{}') as Policy;
      h.writes.push(body);
      policy = body;
      return json(configuration(policy, opts.catalog));
    }
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByRole('region', { name: 'Sensitive data' })).toBeVisible();
  return h;
}

const card = (page: Page) => page.getByRole('region', { name: 'Sensitive data' });
const radio = (page: Page, name: string) => card(page).getByRole('radio', { name: new RegExp(`^${name}\\b`) });
const saveButton = (page: Page) => card(page).getByRole('button', { name: /^Save/ });
const preview = (page: Page) => card(page).getByRole('status');

test('the card, top to bottom: presets with the saved one "In use", no preview, Fine-tune closed, Save disabled', async ({ page }) => {
  await openPolicy(page);
  const c = card(page);
  await expect(c.getByRole('heading', { name: 'Sensitive data' })).toBeVisible();
  await expect(c.getByText('Which values are found and redacted, and when a person has to look.')).toBeVisible();
  const group = c.getByRole('group', { name: 'How much should a person check?' });
  await expect(group.getByRole('radio')).toHaveCount(3);
  await expect(radio(page, 'Strict')).toHaveAccessibleName('Strict A person checks every finding. Cases with nothing found are delivered without a check.');
  await expect(radio(page, 'Balanced')).toHaveAccessibleName('Balanced In use A person checks findings below 80% confidence. The rest are redacted automatically.');
  await expect(radio(page, 'Light')).toHaveAccessibleName('Light A person checks findings below 70% confidence. The rest are redacted automatically.');
  await expect(radio(page, 'Balanced')).toBeChecked();
  await expect(c.locator('.cf-sp-inuse')).toHaveCount(1);
  await expect(c.getByText(/Changing the numbers below by hand shows Custom\. Below the threshold, a finding always goes to a person; nothing is skipped\./)).toBeVisible();
  await expect(preview(page)).toHaveCount(0);
  await expect(c.locator('details')).not.toHaveAttribute('open', '');
  await expect(c.getByRole('heading', { name: 'What is redacted · not changed by presets' })).toBeVisible();
  await expect(c.getByRole('group', { name: 'Sensitive types (4)' })).toBeVisible();
  await expect(saveButton(page)).toHaveText('Save');
  await expect(saveButton(page)).toBeDisabled();
  await expect(c.getByRole('button', { name: 'Reset' })).toBeDisabled();
  // The radio is a real input inside its label, 20 px.
  const size = await radio(page, 'Light').evaluate((el) => { const r = el.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; });
  expect(size).toEqual([20, 20]);
});

test('Strict: only the review mode changes; more findings → no confirm; after saving "In use" moves and the preview hides', async ({ page }) => {
  const h = await openPolicy(page);
  await radio(page, 'Strict').check();
  await expect(saveButton(page)).toHaveText('Save Strict');
  await expect(preview(page)).toContainText('If you save Strict, based on the last 30 days in Residential lending (512 cases)');
  const cases = preview(page).locator('.cf-sp-tile').nth(0);
  await expect(cases).toContainText('Cases a person would have checked');
  await expect(cases.locator('.cf-sp-now')).toHaveText('148');
  await expect(cases.locator('.cf-sp-next')).toHaveText('512');
  await expect(cases.locator('.cf-sp-change')).toHaveText('▲ 364 more');
  const findings = preview(page).locator('.cf-sp-tile').nth(1);
  await expect(findings).toContainText('Findings a person would have checked');
  await expect(findings.locator('.cf-sp-next')).toHaveText('2,410');
  await expect(findings.locator('.cf-sp-change')).toHaveText('▲ 2,070 more');
  await expect(preview(page)).toContainText('An estimate from past findings (type and confidence only). Nothing changes until you save, and only cases received after saving use it.');
  await saveButton(page).click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await expect(page.getByText('Redaction policy saved. New cases use it immediately.')).toBeVisible();
  expect(h.writes).toEqual([{ ...BALANCED, reviewMode: 'REVIEW_ALL', autoRedactThreshold: 0.8 }]);
  await expect(radio(page, 'Strict')).toHaveAccessibleName(/^Strict In use/);
  await expect(radio(page, 'Balanced')).toHaveAccessibleName(/^Balanced A person/);
  await expect(preview(page)).toHaveCount(0);
  await expect(saveButton(page)).toHaveText('Save');
});

test('choosing each preset sets only the mode and threshold; types and the switch stay', async ({ page }) => {
  const policy: Policy = { enabledEntityTypes: ['SSN'], autoRedactThreshold: 0.85, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: false };
  const h = await openPolicy(page, { policy, estimate: () => ({ body: defaultEstimate(new URLSearchParams('reviewMode=REVIEW_ALL')) }) });
  await radio(page, 'Light').check();
  await expect(saveButton(page)).toHaveText('Save Light');
  await radio(page, 'Balanced').check();
  await expect(saveButton(page)).toHaveText('Save Balanced');
  await radio(page, 'Strict').check();
  await expect(saveButton(page)).toHaveText('Save Strict');
  // Strict leaves the threshold where it was (80 from Balanced), shown in Fine-tune.
  await expect(card(page).getByText('Auto-redact when confidence is at least')).toContainText('80%');
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await expect.poll(() => h.writes.length).toBe(1);
  expect(h.writes[0]).toEqual({ enabledEntityTypes: ['SSN'], autoRedactThreshold: 0.8, reviewMode: 'REVIEW_ALL', maskSsnLastFour: false });
});

test('Custom: a saved Custom policy opens Fine-tune and chooses no card; a hand edit shows Custom and "these settings"', async ({ page }) => {
  await openPolicy(page, { policy: { ...BALANCED, autoRedactThreshold: 0.85 } });
  const c = card(page);
  await expect(c.locator('details')).toHaveAttribute('open', '');
  for (const name of ['Strict', 'Balanced', 'Light']) await expect(radio(page, name)).not.toBeChecked();
  await expect(c.locator('.cf-sp-inuse')).toHaveCount(0);
  await expect(c.locator('.cf-sp-hint')).toHaveText('Custom: these settings match no preset. Below the threshold, a finding always goes to a person; nothing is skipped.');
  await expect(c.locator('.cf-sp-hint b')).toHaveText('Custom');

  await radio(page, 'Balanced').check();
  await expect(c.locator('.cf-sp-hint')).toContainText('Changing the numbers below by hand shows Custom.');
  await c.getByLabel(/Auto-redact when confidence is at least/).fill('77');
  for (const name of ['Strict', 'Balanced', 'Light']) await expect(radio(page, name)).not.toBeChecked();
  await expect(c.locator('.cf-sp-hint')).toContainText('Custom: these settings match no preset.');
  await expect(saveButton(page)).toHaveText('Save');
  await expect(preview(page)).toContainText('If you save these settings, based on the last 30 days in Residential lending (512 cases)');
  await expect(c.getByText('Findings below this go to a reviewer.', { exact: true })).toBeVisible();
  // Choosing "Every finding" in Fine-tune is Strict, with Strict's words under the slider (task 0020 A3).
  await expect(c.getByRole('radio', { name: 'Every case' })).toHaveCount(0);
  await c.getByRole('radio', { name: 'Every finding', exact: true }).click();
  await expect(radio(page, 'Strict')).toBeChecked();
  await expect(c.getByText('Every finding goes to a person. Cases with nothing found are delivered without a check.', { exact: true })).toBeVisible();
  await expect(c).not.toContainText('reviewed regardless');
});

test('Save waits for an estimate still in its 300 ms delay: asks at once, then the confirm step', async ({ page }) => {
  const h = await openPolicy(page);
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await radio(page, 'Light').check();
  // Pressed straight away, inside the delay: no answer yet.
  await saveButton(page).click();
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAccessibleDescription('With Light, about 135 fewer findings and 52 fewer cases in the last 30 days would have had a person check them.');
  expect(h.estimates).toHaveLength(1);
  expect(h.writes).toEqual([]);
});

test('Save waits for a slow estimate: "Working out…", the card locked, a second press does nothing, then the confirm step', async ({ page }) => {
  const h = await openPolicy(page, { estimate: () => ({ delayMs: 1500 }) });
  const c = card(page);
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await radio(page, 'Light').check();
  await saveButton(page).click();
  const working = c.getByRole('button', { name: 'Working out…' });
  await expect(working).toBeVisible();
  await expect(working).toHaveAttribute('aria-disabled', 'true');
  await expect(c.getByRole('button', { name: 'Reset' })).toBeDisabled();
  for (const name of ['Strict', 'Balanced', 'Light']) await expect(radio(page, name)).toBeDisabled();
  await expect(c.getByLabel(/Auto-redact when confidence is at least/)).toBeDisabled();
  await expect(c.getByRole('button', { name: 'Credit score' })).toBeDisabled();
  await working.click({ force: true });
  await expect(dialog).toBeVisible();
  await expect(page.getByRole('alertdialog')).toHaveCount(1);
  expect(h.estimates).toHaveLength(1);
  expect(h.writes).toEqual([]);
  await dialog.getByRole('button', { name: 'Save Light' }).click();
  await expect.poll(() => h.writes.length).toBe(1);
  expect(h.writes[0]).toEqual({ ...BALANCED, autoRedactThreshold: 0.7 });
  await expect(radio(page, 'Light')).toBeEnabled();
});

test('Save waits for an estimate that then fails: saves without the confirm step', async ({ page }) => {
  const h = await openPolicy(page, { estimate: () => ({ delayMs: 1000, status: 500 }) });
  await radio(page, 'Light').check();
  await saveButton(page).click();
  await expect(card(page).getByRole('button', { name: 'Working out…' })).toBeVisible();
  await expect.poll(() => h.writes.length).toBe(1);
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  expect(h.writes[0]).toEqual({ ...BALANCED, autoRedactThreshold: 0.7 });
  expect(h.estimates).toHaveLength(1);
});

test('Save waits for a slow estimate that says not enough data: saves without the confirm step', async ({ page }) => {
  const h = await openPolicy(page, { estimate: (q) => ({ delayMs: 1000, body: defaultEstimate(q, 12) }) });
  await radio(page, 'Light').check();
  await saveButton(page).click();
  await expect(card(page).getByRole('button', { name: 'Working out…' })).toBeVisible();
  await expect.poll(() => h.writes.length).toBe(1);
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
});

test('confirm step with Custom on either side: "your current settings" / "these settings"; the audit line keeps the names', async ({ page }) => {
  const h = await openPolicy(page, { policy: { ...BALANCED, autoRedactThreshold: 0.85 }, estimate: (q) => ({ body: {
    ...defaultEstimate(q), current: { ...defaultEstimate(q).current, casesToPerson: 170, findingsToPerson: 410 } } }) });
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  // Custom → Light.
  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await expect(dialog).toHaveAccessibleDescription('With these settings, about 205 fewer findings and 74 fewer cases in the last 30 days would have had a person check them.');
  await expect(dialog.getByRole('button')).toHaveText(['Keep your current settings', 'Save these settings']);
  await expect(dialog).toContainText('shown in the compliance report as Custom → Light.');
  await dialog.getByRole('button', { name: 'Keep your current settings' }).click();
  expect(h.writes).toEqual([]);
  // Custom → Custom (77%).
  await card(page).getByLabel(/Auto-redact when confidence is at least/).fill('77');
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await expect(dialog.getByRole('button')).toHaveText(['Keep your current settings', 'Save these settings']);
  await expect(dialog).toContainText('as Custom → Custom.');
  await dialog.getByRole('button', { name: 'Save these settings' }).click();
  await expect.poll(() => h.writes.length).toBe(1);
  expect(h.writes[0].autoRedactThreshold).toBe(0.77);
});

test('confirm step from a preset to Custom uses the Custom wording', async ({ page }) => {
  await openPolicy(page);
  await card(page).locator('summary').click();
  await card(page).getByLabel(/Auto-redact when confidence is at least/).fill('77');
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await expect(dialog).toHaveAccessibleDescription(/^With these settings, about 40 fewer findings/);
  await expect(dialog.getByRole('button')).toHaveText(['Keep your current settings', 'Save these settings']);
  await expect(dialog).toContainText('as Balanced → Custom.');
});

test('only types or the switch changed: "Save", no preview, no estimate asked', async ({ page }) => {
  const h = await openPolicy(page);
  await card(page).getByRole('button', { name: 'Credit score' }).click();
  await expect(saveButton(page)).toHaveText('Save');
  await expect(saveButton(page)).toBeEnabled();
  await expect(preview(page)).toHaveCount(0);
  await saveButton(page).click();
  await expect.poll(() => h.writes.length).toBe(1);
  expect(h.writes[0].enabledEntityTypes).toEqual(['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER']);
  expect(h.estimates).toEqual([]);
});

test('preview: no change shows "no change"', async ({ page }) => {
  await openPolicy(page, { estimate: (q) => ({ body: { ...defaultEstimate(q), proposed: { ...defaultEstimate(q).proposed, casesToPerson: 148, findingsToPerson: 340 } } }) });
  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-change')).toHaveText(['no change', 'no change']);
});

test('preview: under 20 cases shows "Not enough recent cases to estimate." without tiles, and saving needs no confirm', async ({ page }) => {
  const h = await openPolicy(page, { estimate: (q) => ({ body: defaultEstimate(q, 12) }) });
  await radio(page, 'Light').check();
  await expect(preview(page)).toContainText('If you save Light, based on the last 30 days in Residential lending (12 cases)');
  await expect(preview(page)).toContainText('Not enough recent cases to estimate.');
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(0);
  await saveButton(page).click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await expect.poll(() => h.writes.length).toBe(1);
});

test('preview: while loading it says so; on failure it says so once, and saving needs no confirm', async ({ page }) => {
  let fail = false;
  const h = await openPolicy(page, { estimate: () => (fail ? { status: 500 } : { delayMs: 1500 }) });
  await radio(page, 'Light').check();
  await expect(preview(page)).toHaveText('Working out the estimate…');
  await expect(preview(page)).toHaveAttribute('aria-busy', 'true');
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  // Loading again keeps the box's height.
  const before = (await preview(page).boundingBox())!.height;
  await radio(page, 'Strict').check();
  await expect(preview(page).getByText('Working out the estimate…')).toBeVisible();
  expect(Math.round((await preview(page).boundingBox())!.height)).toBe(Math.round(before));
  await expect(preview(page).locator('.cf-sp-change').first()).toHaveText('▲ 364 more');

  fail = true;
  const asked = h.estimates.length;
  await radio(page, 'Light').check();
  await expect(preview(page)).toHaveText('The estimate couldn’t be worked out. You can still save.');
  await page.waitForTimeout(1200);
  expect(h.estimates.length - asked, 'no retry loop').toBe(1);
  await saveButton(page).click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await expect.poll(() => h.writes.length).toBe(1);
});

test('preview: asks once after the slider stops, and only the latest answer is used', async ({ page }) => {
  const h = await openPolicy(page, {
    policy: { ...BALANCED, autoRedactThreshold: 0.85 },
    // The first answer is slow and would say 999; the later one is quick.
    estimate: (q) => (q.get('threshold') === '0.70' ? { delayMs: 1500, body: { ...defaultEstimate(q), proposed: { ...defaultEstimate(q).proposed, casesToPerson: 999, findingsToPerson: 999 } } } : undefined),
  });
  const slider = card(page).getByLabel(/Auto-redact when confidence is at least/);
  // Quick changes inside the 300 ms delay: one request, for the last value.
  for (const v of ['84', '83', '82', '81']) await slider.fill(v);
  await expect.poll(() => h.estimates.length).toBe(1);
  expect(h.estimates[0]).toContain('threshold=0.81');
  await slider.fill('70');
  await expect.poll(() => h.estimates.length).toBe(2);
  await slider.fill('80');
  await expect.poll(() => h.estimates.length).toBe(3);
  await expect(preview(page).locator('.cf-sp-next').first()).toHaveText('148');
  await page.waitForTimeout(1800);
  await expect(preview(page).locator('.cf-sp-next')).toHaveText(['148', '340']);
  await expect(preview(page)).not.toContainText('999');
});

test('confirm step: Balanced → Light, fewer findings; Esc and "Keep Balanced" reset, "Save Light" saves; focus returns to the save button', async ({ page }) => {
  const h = await openPolicy(page);
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });

  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-change')).toHaveText(['▼ 52 fewer', '▼ 135 fewer']);
  await saveButton(page).click();
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  await expect(dialog).toHaveAccessibleDescription('With Light, about 135 fewer findings and 52 fewer cases in the last 30 days would have had a person check them.');
  await expect(dialog).toContainText('Those findings are still redacted, automatically, without a person looking first. Findings below 70% confidence still go to a person.');
  await expect(dialog).toContainText('This change, and who made it, is recorded in the audit trail and shown in the compliance report as Balanced → Light.');
  await expect(dialog.getByRole('button', { name: 'Keep Balanced' })).toBeFocused();
  await expect(dialog.getByRole('button')).toHaveText(['Keep Balanced', 'Save Light']);
  // Tab stays inside.
  await page.keyboard.press('Tab');
  await expect(dialog.getByRole('button', { name: 'Save Light' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(dialog.getByRole('button', { name: 'Keep Balanced' })).toBeFocused();

  // Esc acts as Keep.
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(radio(page, 'Balanced')).toBeChecked();
  await expect(preview(page)).toHaveCount(0);
  await expect(saveButton(page)).toBeFocused();
  await expect(saveButton(page)).toBeDisabled();

  // "Keep Balanced" does the same.
  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await dialog.getByRole('button', { name: 'Keep Balanced' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(radio(page, 'Balanced')).toBeChecked();
  await expect(saveButton(page)).toBeFocused();
  expect(h.writes).toEqual([]);

  // "Save Light" saves.
  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await dialog.getByRole('button', { name: 'Save Light' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText('Redaction policy saved. New cases use it immediately.')).toBeVisible();
  expect(h.writes).toEqual([{ ...BALANCED, autoRedactThreshold: 0.7 }]);
  await expect(radio(page, 'Light')).toHaveAccessibleName(/^Light In use/);
  await expect(saveButton(page)).toBeFocused();
});

test('confirm step leaves out the cases when they don’t drop', async ({ page }) => {
  await openPolicy(page, { estimate: (q) => ({ body: { ...defaultEstimate(q), proposed: { ...defaultEstimate(q).proposed, casesToPerson: 148, findingsToPerson: 300 } } }) });
  await radio(page, 'Light').check();
  await expect(preview(page).locator('.cf-sp-tile')).toHaveCount(2);
  await saveButton(page).click();
  await expect(page.getByRole('alertdialog')).toHaveAccessibleDescription('With Light, about 40 fewer findings in the last 30 days would have had a person check them.');
});

test('task 0062: packs, locked items and styles; what is saved', async ({ page }) => {
  const h = await openPolicy(page, { catalog: CATALOG, policy: { ...BALANCED, enabledPacks: [], keepLastFourKinds: [], lockedItemsOn: false } });
  const c = card(page);
  await expect(c.getByText('This entity does not have the locked items yet')).toBeVisible();
  for (const [name, on] of [['Credit card number', 'true'], ['Card security code (CVV)', 'false'], ['PIN', 'false']]) {
    const chip = c.getByRole('button', { name: `${name} (on with your next save)` });
    await expect(chip).toHaveAttribute('aria-pressed', on);
    await expect(chip).toHaveAttribute('aria-disabled', 'true');
    await chip.click({ force: true });
    await expect(saveButton(page)).toBeDisabled();
  }
  const pack = c.getByRole('switch', { name: /^FRCP 5\.2/ });
  await expect(pack).toHaveAccessibleName(/Not covered: Names of minors are not found\./);
  await pack.check({ force: true });
  await expect(c.getByRole('button', { name: 'EIN', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await c.getByRole('button', { name: 'EIN', exact: true }).click();
  await expect(c.getByText('Turned off by hand: EIN')).toBeVisible();
  // A style only for kinds that are on and allow it: never a locked item, a date of birth or a credit score.
  await expect(c.getByRole('group', { name: 'What stays visible' }).getByRole('combobox')).toHaveCount(2);
  await c.getByLabel('Social Security Number', { exact: true }).selectOption('KEEP_LAST_FOUR');
  await saveButton(page).click();
  await expect.poll(() => h.writes.length).toBe(1);
  expect(h.writes[0].enabledPacks).toEqual(['FRCP_5_2']);
  expect(h.writes[0].keepLastFourKinds).toEqual(['SSN']);
  expect(h.writes[0].enabledEntityTypes).toContain('ITIN');
  expect(h.writes[0].enabledEntityTypes).not.toContain('EIN');
  expect(h.writes[0]).not.toHaveProperty('lockedItemsOn');
  // The server's answer has the locked items on (the mock echoes what was sent).
  await expect(c.getByRole('button', { name: 'PIN (always on)' })).toHaveAttribute('aria-pressed', 'true');
  if (process.env.PROOF_DIR) await c.screenshot({ path: `${process.env.PROOF_DIR}/policy-catalog.png` });
});
