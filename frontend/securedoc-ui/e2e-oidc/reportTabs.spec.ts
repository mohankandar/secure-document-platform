import { test, expect, type Page } from '@playwright/test';
import { DELIVERED_CASE, STOPPED_CASE, go, signInAs } from '../e2e-a11y/harness';
import { dailyReport, reportTabsAnswer } from './dailyReportFixture';

/**
 * Task 0081: the three Reports tabs, the shared filters, Daily intake and delivery, and the CSV of each tab. The real app on
 * the Vite dev server with the API mocked (the harness of the accessibility suite).
 */

const ZONE = { timeZone: 'America/New_York' };
const tab = (page: Page, name: string) => page.getByRole('tab', { name });
const region = (page: Page, name: string) => page.getByRole('region', { name, exact: true });

async function openReports(page: Page, role: 'business' | 'viewer' | 'supervisor' | 'it', path = '/reports') {
  await signInAs(page, role, path, ZONE);
}

async function dayAnswer(page: Page, change: (day: ReturnType<typeof dailyReport>) => void, asked: URLSearchParams[] = []) {
  await page.route('**/api/v1/operations/reports/daily?**', (route) => {
    asked.push(new URL(route.request().url()).searchParams);
    const day = dailyReport();
    change(day);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(reportTabsAnswer('/operations/reports/daily', () => day)) });
  });
}

async function proof(page: Page, name: string) {
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/${name}.png`, fullPage: true });
}

test('three tabs with the keyboard; the period and filters are shared', async ({ page }) => {
  const asked: URLSearchParams[] = [];
  const reportAsked: URLSearchParams[] = [];
  page.on('request', (r) => { if (r.url().includes('/operations/reports?')) reportAsked.push(new URL(r.url()).searchParams); });
  await openReports(page, 'business');
  await dayAnswer(page, () => undefined, asked);
  await expect(tab(page, 'Overview')).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('radio', { name: '30 days' })).toHaveAttribute('aria-checked', 'true');
  await expect(region(page, 'Service levels')).toBeVisible();
  await expect(region(page, 'Pipeline')).toHaveCount(0);
  await proof(page, 'overview');

  await tab(page, 'Overview').focus();
  await page.keyboard.press('ArrowRight');
  await expect(tab(page, 'Daily intake and delivery')).toBeFocused();
  await expect(page).toHaveURL(/\/reports\?tab=daily$/);
  // Until a period is chosen the Daily tab shows today.
  await expect(page.getByRole('radio', { name: 'Today' })).toHaveAttribute('aria-checked', 'true');
  await expect(region(page, 'Received today')).toContainText('47 cases');
  await page.getByRole('radio', { name: 'Yesterday' }).click();
  await expect(region(page, 'Received yesterday')).toBeVisible();
  await page.getByRole('combobox', { name: 'Source system' }).selectOption({ label: 'Branch scanners' });
  await page.getByRole('combobox', { name: 'Level' }).selectOption({ label: 'Highly Confidential' });
  await expect.poll(() => `${asked.at(-1)?.get('sourceSystem')} ${asked.at(-1)?.get('level')}`).toBe('c-scan HIGHLY_CONFIDENTIAL');
  const from = asked.at(-1)!.get('from');

  await tab(page, 'Operations').click();
  await expect(page.getByRole('radio', { name: 'Yesterday' })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByRole('combobox', { name: 'Source system' })).toHaveValue('c-scan');
  await expect.poll(() => `${reportAsked.at(-1)?.get('sourceSystem')} ${reportAsked.at(-1)?.get('level')} ${reportAsked.at(-1)?.get('from')}`)
    .toBe(`c-scan HIGHLY_CONFIDENTIAL ${from}`);
});

test('Daily intake and delivery: every part, top to bottom', async ({ page }) => {
  await openReports(page, 'business', '/reports?tab=daily');
  const panel = page.getByRole('tabpanel');
  await expect(panel.getByRole('note')).toHaveText(/^Branch scanners has sent nothing since 9:12.AM\. It usually sends every hour on a Tuesday\.$/);
  const received = region(page, 'Received today');
  for (const words of ['47 cases', '182 documents · 1,463 pages', '3 Urgent', /^Usual for a \w+day: 38–52$/]) await expect(received.getByText(words)).toBeVisible();
  const exported = region(page, 'Exported today');
  for (const words of ['41 cases', '160 documents · 1,302 pages', 'plus 18 split documents', /^Usual for a \w+day: 35–50$/]) await expect(exported.getByText(words)).toBeVisible();
  const open = region(page, 'Not exported yet');
  await expect(open).toContainText('9 cases');
  await expect(open).toContainText('Oldest waiting 2 h 15 min');
  expect(await open.locator('dl > div').allInnerTexts().then((t) => t.map((x) => x.replace(/\s+/g, ' ')))).toEqual(
    ['Ready for export 2', 'Waiting to retry 1', 'Destination paused 4', 'Waiting for you 2']);
  const attention = region(page, 'Needs attention');
  await expect(attention).toContainText('6 cases');

  const reasons = region(page, 'Needs attention, by reason').getByRole('row');
  await expect(reasons).toHaveCount(6);
  await expect(reasons.nth(3)).toContainText(/LV-PRC-004 .*Stopped in processing.*2/);
  await expect(region(page, 'Needs attention, by reason')).not.toContainText(/what to do/i);
  const destinations = region(page, 'Exported today, by destination').getByRole('row');
  await expect(destinations.nth(1)).toContainText(/Records store.*41 cases.*160.*1,302.*1.*1/);
  await expect(destinations.nth(2)).toContainText(/Title desk.*Paused.*18 split documents.*18.*54/);
  const sources = region(page, 'Received today, by source system').getByRole('row');
  await expect(sources.nth(1)).toHaveAttribute('data-quiet', 'true');
  await expect(sources.nth(1)).toContainText('Quiet');
  await expect(sources.nth(2)).toContainText('Loan origination');
  expect((await sources.nth(2).getByRole('cell').allInnerTexts()).slice(1)).toEqual(['38', '2', '2', '30', '6', '2']);
  await expect(sources.nth(3)).toContainText(/Nov 23, 3:05/);
  await expect(region(page, 'How today’s cases were handled')).toContainText('Finished by hand after a stop');
  await expect(region(page, 'By sensitivity level').getByRole('row').nth(3)).toContainText(/Highly Confidential.*12.*12/);
  await expect(panel).toContainText('Counts only: no names, file names or content.');
  await expect(page.locator('.cf-reports')).not.toContainText(/sending system/i);
  await page.evaluate(() => window.scrollTo(0, 0)); await proof(page, 'daily');

  // The tile goes to the reasons; a Business Administrator opens no case, so the counts are plain numbers.
  await attention.getByRole('link', { name: '6 cases' }).click();
  await expect(page).toHaveURL(/#cf-dr-reasons$/);
  // Task 0082: each code (5) and the note link to the error code guide; no row links to cases.
  await expect(region(page, 'Needs attention, by reason').locator('a:not([href^="/help/error-codes"])')).toHaveCount(0);
  await expect(region(page, 'Needs attention, by reason').locator('tbody a[href^="/help/error-codes/LV-"]')).toHaveCount(5);
  await expect(open.getByRole('link')).toHaveCount(0);
  await expect(panel.getByRole('link', { name: 'Open the connection' })).toHaveCount(0);
});

test('with a level chosen, refusals and duplicates are not shown as counted', async ({ page }) => {
  await openReports(page, 'business', '/reports');
  await dayAnswer(page, (day) => { day.refusalsCounted = false; day.attention.refused = 0; day.reasons = day.reasons.filter((r) => r.where !== 'REFUSED_AT_INTAKE'); });
  await tab(page, 'Daily intake and delivery').click();
  await page.getByRole('combobox', { name: 'Level' }).selectOption('CONFIDENTIAL');
  await expect(region(page, 'Needs attention')).toContainText('Refused and duplicate counts are not known by level');
  const row = region(page, 'Received today, by source system').getByRole('row').nth(2);
  expect((await row.getByRole('cell').allInnerTexts()).slice(2, 4)).toEqual(['—', '—']);
});

test('a quiet, empty day says so plainly', async ({ page }) => {
  await openReports(page, 'viewer', '/reports');
  await dayAnswer(page, (day) => Object.assign(day, { quiet: [], reasons: [], destinations: [], sources: [],
    notExported: { cases: 0, ready: 0, waitingToRetry: 0, destinationPaused: 0, waitingForYou: 0, urgent: 0, oldestWaitingSeconds: null },
    attention: { refused: 0, stopped: 0, exportFailed: 0, rejected: 0 } }));
  await tab(page, 'Daily intake and delivery').click();
  await expect(region(page, 'Not exported yet')).toContainText('Nothing is waiting');
  await expect(page.getByText('Nothing needs attention.')).toBeVisible();
  await expect(page.getByRole('note')).toHaveCount(0);
});

test('Operations: the stage view of the reader’s own entity and today’s lower part; Intake channels is gone', async ({ page }) => {
  const asked: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/queue')) asked.push(new URL(r.url()).pathname); });
  await openReports(page, 'business', '/reports?tab=operations');
  const queue = region(page, 'Queue');
  await expect(queue.getByRole('row')).toHaveCount(6);
  await expect(queue).toContainText('the filters do not change it');
  for (const name of ['Pipeline', 'Backlog age', 'Retention', 'Timing and volume']) await expect(region(page, name)).toBeVisible();
  await expect(page.getByText('Intake channels')).toHaveCount(0);
  await expect(page.getByText('What LexaVault did for you')).toHaveCount(0);
  // Not linked to cases this person cannot open.
  await expect(region(page, 'Pipeline').getByRole('link')).toHaveCount(0);
  expect(new Set(asked)).toEqual(new Set(['/api/v1/operations/reports/queue']));
  await proof(page, 'operations');
});

test('a Report Viewer: Daily in full, one line on Operations, no CSV and no stage view asked for', async ({ page }) => {
  const asked: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/queue')) asked.push(r.url()); });
  await openReports(page, 'viewer', '/reports?tab=daily');
  await expect(region(page, 'Received today')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Export CSV' })).toHaveCount(0);
  await tab(page, 'Operations').click();
  await expect(page.getByText('More detail is available to Business Administrators.')).toBeVisible();
  await expect(region(page, 'Queue')).toHaveCount(0);
  expect(asked).toEqual([]);
});

test('Export CSV exports the tab that is open', async ({ page }) => {
  const asked: URL[] = [];
  await openReports(page, 'business');
  await page.route('**/api/v1/operations/reports/*/export?**', (route) => {
    asked.push(new URL(route.request().url()));
    return route.fulfill({ status: 200, contentType: 'text/csv', body: '"section","item","measure","value"\r\n' });
  });
  for (const [name, path] of [['Overview', 'overview'], ['Daily intake and delivery', 'daily'], ['Operations', 'operations']] as const) {
    await tab(page, name).click();
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export CSV' }).click()]);
    await download.path();
    expect(download.suggestedFilename()).toMatch(new RegExp(`^lexavault-${path}-\\d{4}-\\d\\d-\\d\\d-to-\\d{4}-\\d\\d-\\d\\d\\.csv$`));
    expect(asked.at(-1)!.pathname).toBe(`/api/v1/operations/reports/${path}/export`);
    expect(asked.at(-1)!.searchParams.get('sourceSystem')).toBe('ALL');
  }
});

test('an answer of the wrong shape fails with the problem card; the tabs stay', async ({ page }) => {
  await openReports(page, 'business', '/reports');
  await page.route('**/api/v1/operations/reports/daily?**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await tab(page, 'Daily intake and delivery').click();
  await expect(page.getByRole('alert', { name: "Reports couldn't be loaded" })).toBeVisible();
  await tab(page, 'Overview').click();
  await expect(region(page, 'Service levels')).toBeVisible();
});

test('Cases opens with one reason code from Reports, and shows all again', async ({ page }) => {
  await openReports(page, 'supervisor', '/intake');
  await page.route('**/api/v1/packages', (route) => route.request().method() === 'GET' ? route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify([{ ...STOPPED_CASE, stopReasonCode: 'LV-PRC-004' }, DELIVERED_CASE]) }) : route.fallback());
  await go(page, '/?code=LV-PRC-004');
  await expect(page.getByText(/^Only cases with .*LV-PRC-004/)).toBeVisible();
  const rows = page.locator('a.cf-case');
  await expect(rows).toHaveCount(1);
  await page.getByRole('button', { name: 'Show all cases' }).click();
  await expect(page.getByText(/^Only cases with/)).toHaveCount(0);
  await expect(rows).toHaveCount(2);
});
