import { test, expect, type Page } from '@playwright/test';
import { LV_CODES } from '../src/studio/lvErrorCodes.generated';
import { go, signInAs, type Who } from '../e2e-a11y/harness';
import { dailyReport, reportTabsAnswer } from './dailyReportFixture';

/** Task 0082: Help › Error codes, its deep links, and the links from the places that show a code. */

const CODES = Object.keys(LV_CODES);
const entry = (page: Page, code: string) => page.locator(`details[data-code="${code}"]`);
const search = (page: Page) => page.getByRole('textbox', { name: 'Find a code or words' });

async function proof(page: Page, name: string, fullPage = false) {
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/${name}.png`, fullPage });
}

for (const role of ['processor', 'supervisor', 'business', 'viewer', 'it', 'ops'] as Who[]) {
  test(`${role}: the account menu opens the guide, with every code`, async ({ page }) => {
    await signInAs(page, role);
    const api: string[] = [];
    await page.getByRole('button', { name: /account menu/ }).click();
    await page.getByRole('menuitem', { name: 'Help: error codes' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Error codes' })).toBeVisible();
    await expect(page).toHaveURL(/\/help\/error-codes$/);
    await expect(page).toHaveTitle(/^Error codes · Help · /);
    page.on('request', (r) => { if (/help|error-codes/.test(new URL(r.url()).pathname) && r.url().includes('/api/')) api.push(r.url()); });
    await expect(page.locator('details[data-code]')).toHaveCount(CODES.length);
    expect(api).toEqual([]);
  });
}

test('search by code and by words; one hit is open; clearing brings everything back', async ({ page }) => {
  await signInAs(page, 'business', '/help/error-codes');
  const status = page.locator('.cf-help-bar [role="status"]');
  await expect(status).toHaveText(`${CODES.length} codes`);
  await proof(page, 'guide-page');
  await search(page).fill('lv-dlv-002');
  await expect(status).toHaveText('1 code');
  await expect(entry(page, 'LV-DLV-002').getByText(LV_CODES['LV-DLV-002'].supportTells)).toBeVisible();
  await search(page).fill('watched folder');
  await expect(entry(page, 'LV-INT-034')).toBeVisible();
  await expect(entry(page, 'LV-DLV-002')).toHaveCount(0);
  await search(page).fill('no such words at all');
  await expect(status).toHaveText('No code matches');
  await page.getByRole('button', { name: 'Clear search' }).click();
  await expect(status).toHaveText(`${CODES.length} codes`);
});

test('a deep link opens its entry with all its texts; a code in a step leads on to that entry', async ({ page }) => {
  await signInAs(page, 'it', '/help/error-codes/LV-SYS-006');
  const e = LV_CODES['LV-SYS-006'];
  const it = entry(page, e.code);
  await expect(it).toHaveAttribute('open', '');
  await expect(it).toBeInViewport();
  await expect(it.locator('summary')).toBeFocused();
  for (const text of [e.title, e.description, e.supportTells, 'System', 'IT Admin', ...e.steps]) await expect(it).toContainText(text);
  await expect(page.locator('details[data-code][open]')).toHaveCount(1);
  await proof(page, 'one-entry');
  await it.getByRole('link', { name: 'LV-PRC-005' }).click();
  await expect(page).toHaveURL(/LV-PRC-005$/);
  await expect(entry(page, 'LV-PRC-005')).toHaveAttribute('open', '');
});

test('an unknown code says so and shows the guide', async ({ page }) => {
  await signInAs(page, 'viewer', '/help/error-codes/LV-XYZ-999');
  await expect(page.locator('.cf-notice')).toHaveText('This code isn\'t in the guide. Check how it is written, or search below.');
  await expect(page.locator('details[data-code]')).toHaveCount(CODES.length);
  await expect(page.locator('details[data-code][open]')).toHaveCount(0);
});

test('Reports: every code row links to its entry, for every code of the catalogue', async ({ page }) => {
  await signInAs(page, 'business', '/reports');
  await page.route('**/api/v1/operations/reports/daily?**', (route) => {
    const day = dailyReport();
    day.reasons = CODES.map((code) => ({ code, where: 'STOPPED_IN_PROCESSING', cases: 1 })) as typeof day.reasons;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(reportTabsAnswer('/operations/reports/daily', () => day)) });
  });
  await page.getByRole('tab', { name: 'Daily intake and delivery' }).click();
  const region = page.getByRole('region', { name: 'Needs attention, by reason', exact: true });
  const rows = region.locator('tbody tr');
  await expect(rows).toHaveCount(CODES.length);
  const links = await rows.evaluateAll((all) => all.map((row) => {
    const code = row.querySelector('code')?.textContent ?? '';
    const a = row.querySelector('code')?.closest('a');
    return { code, href: a?.getAttribute('href') ?? null, target: a?.target, rel: a?.rel };
  }));
  expect(links).toEqual(CODES.map((code) => ({ code, href: `/help/error-codes/${code}`, target: '_blank', rel: 'noopener' })));
  await expect(region.getByRole('link', { name: 'error code guide' })).toHaveAttribute('href', '/help/error-codes');

  // The new tab has no session: it shows the entry by itself, asks nothing of the server and needs no sign-in.
  const calls: string[] = [];
  page.context().on('request', (r) => { if (!r.isNavigationRequest() && r.frame().page() !== page && (new URL(r.url()).pathname.startsWith('/api/') || r.url().includes('idp.test'))) calls.push(r.url()); });
  const [tab] = await Promise.all([page.waitForEvent('popup'), region.getByRole('link', { name: 'LV-PRC-004', exact: true }).click()]);
  await expect(entry(tab, 'LV-PRC-004')).toHaveAttribute('open', '');
  expect(await tab.evaluate(() => window.sessionStorage.length)).toBe(0);
  expect(await tab.evaluate(() => window.opener)).toBeNull();
  expect(calls).toEqual([]);
  await expect(page.getByRole('tab', { name: 'Daily intake and delivery' })).toHaveAttribute('aria-selected', 'true');
});

test('Support › Status: every code links to its entry', async ({ page }) => {
  await signInAs(page, 'it', '/support/status');
  const links = page.locator('a.cf-lv-link');
  await expect(links.first()).toBeVisible();
  for (const a of await links.all()) {
    await expect(a).toHaveAttribute('href', `/help/error-codes/${await a.textContent()}`);
    await expect(a).toHaveAttribute('target', '_blank');
  }
  await go(page, '/help/error-codes');
  await expect(page.getByRole('heading', { level: 1, name: 'Error codes' })).toBeVisible();
});

test('a code that a person without a role is shown leads to the guide too', async ({ page }) => {
  await signInNone(page);
  const [tab] = await Promise.all([page.waitForEvent('popup'), page.getByRole('link', { name: 'LV-AUT-002' }).click()]);
  await expect(entry(tab, 'LV-AUT-002')).toHaveAttribute('open', '');
});

async function signInNone(page: Page) {
  const { mockApi, mockIdentityProvider } = await import('../e2e-a11y/harness');
  await mockIdentityProvider(page);
  await mockApi(page, 'none');
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('heading', { name: 'No access to LexaVault · LV-AUT-002' })).toBeVisible();
}
