import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U007: Admin for the Business Administrator (UI spec findings 11, 12, 13, 17, 23): Off / Observe / On with
 * the radio beside its text, the AI version in plain words with codes under Technical details, Keeping and deleting
 * in days, Report targets with the new review percentage, and Case fields with the rules spelled out.
 *
 * Same harness as reports.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c7c7c7c7-0000-4000-8000-00000000000c', name: 'Admin Entity', slug: 'admin-entity' };
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


type Body = Record<string, unknown>;

function configuration() {
  return {
    identifiers: [
      { id: 'd1', key: 'loan_number', label: 'Loan number', required: true, primary: true, searchable: true, extractable: true, displayable: true, displayOrder: 1,
        validationType: 'TEXT', customPattern: '[A-Za-z0-9]+', exactLength: null, minLength: 6, maxLength: 12, caseNormalization: 'UPPER', stripSeparators: true, allowedValues: [] },
      { id: 'd2', key: 'borrower_name', label: 'Borrower name', required: false, primary: false, searchable: true, extractable: true, displayable: true, displayOrder: 2,
        validationType: 'TEXT', exactLength: null, minLength: null, maxLength: 120, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [], customPattern: null },
      { id: 'd3', key: 'property_state', label: 'Property state', required: false, primary: false, searchable: false, extractable: false, displayable: true, displayOrder: 3,
        validationType: 'DROPDOWN', exactLength: null, minLength: null, maxLength: null, caseNormalization: 'UPPER', stripSeparators: false, allowedValues: ['CA', 'NV', 'AZ', 'OR'], customPattern: null },
    ],
    dashboardColumns: [
      { key: 'identifier:loan', label: 'Loan number', kind: 'IDENTIFIER', primary: true, allowed: true, defaultVisible: true, displayOrder: 0 },
      { key: 'received', label: 'Received', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 1 },
      { key: 'intakeSource', label: 'Intake Source', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: false, displayOrder: 2 },
      { key: 'exportStatus', label: 'Export Status', kind: 'SYSTEM', primary: false, allowed: false, defaultVisible: false, displayOrder: 3 },
      { key: 'lastUpdated', label: 'Last Updated', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: false, displayOrder: 4 },
    ],
    sensitiveDataPolicy: { enabledEntityTypes: ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE'], autoRedactThreshold: 0.8, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true },
    lifecyclePolicy: { originalRetentionHours: 72, redactedCacheHours: 168, neverExportedHoldHours: 336, finalApprovalRequired: true, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 720 },
    documentIntelligence: {
      mode: 'OBSERVE', activeBundleVersion: '2026.11.1', lastKnownGoodBundleVersion: 'r11-baseline-v1', configurationVersion: 4, baselineDiscoveryRequired: false,
      availableBundles: [
        { version: '2026.11.1', status: 'PROMOTED', modelVersion: 'qwen3.5:9b', promptVersion: 'p7', detectorVersion: 'd3', ocrRulesVersion: 'o2', profileVersion: 'none',
          validationRulesVersion: 'v5', thresholdsVersion: 't4', description: 'Improved reading of deeds of trust.', promotedAt: '2026-11-01T15:00:00Z' },
        { version: 'r11-baseline-v1', status: 'PROMOTED', modelVersion: 'qwen3.5:4b', promptVersion: 'r11', detectorVersion: 'r11', ocrRulesVersion: 'r11', profileVersion: 'none',
          validationRulesVersion: 'r11', thresholdsVersion: 'r11', description: 'Frozen baseline.', promotedAt: '2026-10-02T15:00:00Z' },
      ],
    },
    reportingConfiguration: {
      dimensions: [{ definitionId: 'd3', key: 'property_state', label: 'Property state', enabled: true, displayOrder: 1 }],
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70, reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, minutesPerPage: 1,
    },
  };
}

async function openAdmin(page: Page, writes: { path: string; body: Body }[]) {
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: ['BUSINESS_ADMIN'] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'admin.user@example.test', roles: ['BUSINESS_ADMIN'], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: CAPS });
    }
    if (url.pathname === '/api/v1/business-admin/configuration') return json(configuration());
    if (request.method() === 'PUT' && url.pathname.startsWith('/api/v1/business-admin/')) {
      const body = JSON.parse(request.postData() || '{}') as Body;
      writes.push({ path: url.pathname, body });
      const next = configuration();
      if (url.pathname.endsWith('/reporting-configuration')) Object.assign(next.reportingConfiguration, body);
      if (url.pathname.endsWith('/lifecycle-policy')) Object.assign(next.lifecyclePolicy, body);
      return json(next);
    }
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByRole('region', { name: 'Document intelligence' })).toBeVisible();
}

test('Document intelligence: Off / Observe / On with the radio left of its text, and the version in plain words', async ({ page }) => {
  await openAdmin(page, []);
  const card = page.getByRole('region', { name: 'Document intelligence' });
  for (const name of ['Off', 'Observe', 'On']) await expect(card.getByRole('radio', { name: new RegExp(`^${name}\\b`) })).toBeVisible();
  await expect(card.getByRole('radio', { name: /^Observe/ })).toBeChecked();
  // Radio on the left, text right beside it, the three side by side at 1440 px.
  const boxes = await card.locator('.cf-ad-radio').evaluateAll((els) => els.map((label) => {
    const r = label.querySelector('input')!.getBoundingClientRect();
    const t = label.querySelector('strong')!.getBoundingClientRect();
    const l = label.getBoundingClientRect();
    return { radioLeft: r.left - l.left, gap: t.left - r.right, top: l.top };
  }));
  for (const b of boxes) { expect(b.radioLeft).toBeLessThan(20); expect(b.gap).toBeGreaterThanOrEqual(0); expect(b.gap).toBeLessThan(20); }
  expect(new Set(boxes.map((b) => Math.round(b.top))).size).toBe(1);

  const select = card.getByLabel('AI version in use');
  await expect(select.locator('option:checked')).toHaveText(/^November 2026 update · released Nov 1, 2026$/);
  await expect(card.getByText('Improved reading of deeds of trust. Only tested and released versions are offered.')).toBeVisible();
  await expect(card.getByText('Fallback if something goes wrong:')).toBeVisible();
  await expect(card.getByText('October 2026 update', { exact: true })).toBeVisible();
  await expect(card.getByRole('button', { name: 'Use this version as the fallback' })).toBeEnabled();
  await expect(card.getByRole('button', { name: 'Go back to fallback…' })).toBeEnabled();
  // Codes only under Technical details, closed by default.
  const details = card.locator('details');
  await expect(details).not.toHaveAttribute('open', '');
  await expect(card.getByText('qwen3.5:9b')).toBeHidden();
  await card.getByText('Technical details').click();
  await expect(card.getByText('qwen3.5:9b')).toBeVisible();
  await expect(details.getByText('2026.11.1', { exact: true })).toBeVisible();
  const outside = await card.evaluate((el) => { const c = el.cloneNode(true) as HTMLElement; c.querySelectorAll('details, option').forEach((d) => d.remove()); return c.innerText; });
  for (const code of [/last known good/i, /bundle/i, /\bv4\b/, /r11-baseline/, /Disabled|Enabled/]) expect(outside).not.toMatch(code);
});

test('Keeping and deleting: plain names, days where whole days, originals in hours, and the saved hours', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Keeping and deleting' });
  await expect(card.getByLabel('Keep originals for', { exact: true })).toHaveValue('72');
  await expect(card.locator('.cf-ad-duration-unit').first()).toHaveText('hours');
  await expect(card.getByLabel('Keep redacted copies for', { exact: true })).toHaveValue('7');
  await expect(card.getByLabel('Keep redacted copies for: unit')).toHaveValue('days');
  await expect(card.getByLabel('Keep stopped cases for', { exact: true })).toHaveValue('14');
  await expect(card.getByText('Up to 30 days.')).toBeVisible();
  await expect(card.getByText("Require a supervisor's approval before delivery")).toBeVisible();
  await expect(card.getByText('A case can be processed again at most 3 times (set by LexaVault).')).toBeVisible();
  await expect(card).not.toContainText(/Hold if never delivered|Retention and approval|export cycle/i);

  await card.getByLabel('Keep stopped cases for', { exact: true }).fill('10');
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toEqual({ path: '/api/v1/business-admin/lifecycle-policy',
    body: { originalRetentionHours: 72, redactedCacheHours: 168, neverExportedHoldHours: 240, finalApprovalRequired: true } });
});

test('Keeping and deleting: more than 30 days, or part of an hour, is marked and not saved', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Keeping and deleting' });
  await card.getByLabel('Keep redacted copies for', { exact: true }).fill('31');
  await expect(card.getByText('Enter a whole number of hours from 1 hour up to 30 days.')).toBeVisible();
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect(card.getByText('Redacted copies can be kept for up to 30 days.')).toBeVisible();
  await card.getByLabel('Keep redacted copies for', { exact: true }).fill('7');
  await card.getByLabel('Keep originals for', { exact: true }).fill('1.5');
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect(card.getByText('Each time must be a whole number of hours, at least 1 hour.')).toBeVisible();
  expect(writes).toHaveLength(0);
});

test('Report targets: the three targets as sentences, the new review percentage saved', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Report targets' });
  await expect(card.getByLabel('Deliver within', { exact: true })).toHaveValue('4');
  await expect(card.getByLabel('Deliver within: unit')).toHaveValue('hours');
  await expect(card.getByLabel('Share of cases delivered in time')).toHaveValue('95');
  await expect(card.getByLabel('Cases needing no review')).toHaveValue('70');
  await expect(card.getByLabel('Pick up reviews within', { exact: true })).toHaveValue('1');
  await expect(card.getByLabel('Pick up reviews within: unit')).toHaveValue('hours');
  await expect(card.getByLabel('Share of reviews picked up in time')).toHaveValue('80');
  await expect(card.getByText('Break reports down by')).toBeVisible();
  await expect(card).not.toContainText(/straight-through/i);

  await card.getByLabel('Share of reviews picked up in time').fill('85');
  await card.getByLabel('Pick up reviews within', { exact: true }).fill('2');
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0].path).toBe('/api/v1/business-admin/reporting-configuration');
  expect(writes[0].body).toMatchObject({ deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70,
    reviewWaitTargetMinutes: 120, reviewWaitTargetPercent: 85, enabledDimensionDefinitionIds: ['d3'] });
});

test('Report targets: a review percentage over 100 is marked and not saved', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Report targets' });
  await card.getByLabel('Share of reviews picked up in time').fill('101');
  await expect(card.getByText('Share of reviews picked up in time: enter a number from 0 to 100.')).toBeVisible();
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect(card.getByText('The review target must be a whole number from 0% to 100%.')).toBeVisible();
  expect(writes).toHaveLength(0);
});

test('Case fields: names as configured, headings over their columns, rules spelled out', async ({ page }) => {
  await openAdmin(page, []);
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Case fields/ }).click();
  const card = page.getByRole('region', { name: 'Case fields' });
  const table = card.getByRole('table');
  for (const h of ['Field', 'Main ID', 'Required', 'Searchable', 'AI fills in', 'Shown']) await expect(table.getByRole('columnheader', { name: h, exact: true })).toBeVisible();
  // Shown as configured, not in capitals.
  const cell = table.getByRole('rowheader').first();
  await expect(cell.locator('.cf-ad-cell-title')).toHaveCSS('text-transform', 'none');
  await expect(table.getByRole('columnheader', { name: 'Field', exact: true })).toHaveCSS('text-transform', 'none');
  await expect(cell).toContainText('Loan number');
  await expect(card.getByText('Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed')).toBeVisible();
  await expect(card.getByText('Text · up to 120 characters')).toBeVisible();
  await expect(card.getByText('One of 4 allowed values · saved in capitals')).toBeVisible();
  // Each heading sits over its column.
  const heads = await table.locator('thead th').evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); return [r.left, r.right]; }));
  const firstRow = await table.locator('tbody tr').first().locator('th, td').evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); return [r.left, r.right]; }));
  expect(firstRow.length).toBe(heads.length);
  heads.forEach(([l, r], i) => { expect(Math.abs(l - firstRow[i][0])).toBeLessThan(1); expect(Math.abs(r - firstRow[i][1])).toBeLessThan(1); });
  const fieldHead = await table.getByRole('columnheader', { name: 'Field', exact: true }).evaluate((e) => getComputedStyle(e).textAlign);
  expect(fieldHead).toBe('left');

  // Columns on Cases: LexaVault's columns by key in sentence case, case fields as configured (owner's U007 answer).
  const columns = page.getByRole('region', { name: 'Columns on Cases' });
  const names = await columns.locator('.cf-ad-row-label').evaluateAll((els) => els.map((e) => e.firstChild?.textContent ?? ''));
  expect(names).toEqual(['Loan number', 'Received', 'Intake source', 'Export status', 'Last updated']);
  await expect(columns).not.toContainText(/Intake Source|Export Status|Last Updated/);
});

test('Sensitive data: the types are headed "Sensitive types", never "entities"', async ({ page }) => {
  await openAdmin(page, []);
  const card = page.getByRole('region', { name: 'Sensitive data' });
  await expect(card.getByRole('group', { name: 'Sensitive types (4)' })).toBeVisible();
  await expect(card).not.toContainText(/entit(y|ies)/i);
});

// --- U024: the time saved estimate in Report targets -------------------------------------------------------------

test('U024: Report targets: "Time saved estimate", New, the minutes field and its note; 2.5 is saved', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Report targets' });
  await expect(card.getByText('Time saved estimate', { exact: false })).toBeVisible();
  const pill = card.locator('.cf-ad-new-pill');
  await expect(pill).toHaveText('New');
  const field = card.getByRole('spinbutton', { name: /^A person takes .*minutes to check one page by hand$/ });
  await expect(field).toHaveValue('1');
  // One <label> around the sentence and the field.
  expect(await field.evaluate((el) => el.closest('label')?.textContent?.replace(/\s+/g, ' ').trim()))
    .toBe('A person takes minutes to check one page by hand');
  for (const [name, value] of [['type', 'number'], ['inputmode', 'decimal'], ['min', '0.5'], ['max', '30'], ['step', '0.5']]) {
    await expect(field).toHaveAttribute(name, value);
  }
  const box = (await field.boundingBox())!;
  expect([Math.round(box.width), Math.round(box.height)]).toEqual([80, 40]);
  expect(await field.evaluate((el) => getComputedStyle(el).fontFamily)).toContain('IBM Plex Mono');
  await expect(card.getByText('Used for "Review time saved" on Reports and in the compliance report. Default 1 minute: deliberately low, so '
    + 'the estimate never overclaims. Set it from your own team\'s experience; changing it is recorded in the audit trail.', { exact: true })).toBeVisible();
  // Under the three targets, after a top border, before "Break reports down by".
  const block = card.locator('.cf-ad-timesaved');
  expect(await block.evaluate((el) => getComputedStyle(el).borderTopWidth)).toBe('1px');
  const targets = (await card.locator('.cf-ad-sentences').boundingBox())!;
  const blockBox = (await block.boundingBox())!;
  const breakdown = (await card.getByText('Break reports down by').boundingBox())!;
  expect(blockBox.y).toBeGreaterThanOrEqual(targets.y + targets.height);
  expect(breakdown.y).toBeGreaterThanOrEqual(blockBox.y + blockBox.height);

  await field.fill('2.5');
  await card.getByRole('button', { name: 'Save changes' }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0].path).toBe('/api/v1/business-admin/reporting-configuration');
  expect(writes[0].body).toMatchObject({ minutesPerPage: 2.5, deliveryTargetMinutes: 240, reviewWaitTargetPercent: 80 });
  await expect(field).toHaveValue('2.5');
  await expect(card.getByRole('button', { name: 'Save changes' })).toBeDisabled();
});

test('U024: Report targets: minutes outside 0.5–30 or not in steps of 0.5 are marked, and Save is disabled', async ({ page }) => {
  const writes: { path: string; body: Body }[] = [];
  await openAdmin(page, writes);
  const card = page.getByRole('region', { name: 'Report targets' });
  const field = card.getByRole('spinbutton', { name: /^A person takes .*minutes to check one page by hand$/ });
  const save = card.getByRole('button', { name: 'Save changes' });
  const error = card.getByText('Enter a number from 0.5 to 30, in steps of 0.5.', { exact: true });
  await expect(error).toHaveCount(0);
  for (const bad of ['0.4', '30.5', '1.25', '0', '']) {
    await field.fill(bad);
    await expect(error, `"${bad}" is marked`).toBeVisible();
    await expect(field).toHaveAttribute('aria-invalid', 'true');
    await expect(save, `"${bad}" cannot be saved`).toBeDisabled();
  }
  await field.fill('30');
  await expect(error).toHaveCount(0);
  await expect(field).not.toHaveAttribute('aria-invalid', 'true');
  await expect(save).toBeEnabled();
  await field.fill('1.25');
  await expect(save).toBeDisabled();
  // Reset puts the saved value back and clears the mark.
  await card.getByRole('button', { name: 'Reset' }).click();
  await expect(field).toHaveValue('1');
  await expect(error).toHaveCount(0);
  await expect(save).toBeDisabled();
  expect(writes).toHaveLength(0);
});
