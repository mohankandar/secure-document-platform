import { test, expect, type Page } from '@playwright/test';
import { APPROVAL_CASE, DELIVERED_CASE, EXPORT_CASES, REDACTING_CASE, REVIEW_CASE, STOPPED_CASE, type Who } from './harness';
import { openAt } from './states0089';

/**
 * Task 0108 (Cases at volume), parts 2 to 7: the six stage boxes, the groups (Needs you open, In progress and Exported
 * today as one line each), true counts at 400+ cases, the stopped and final cases off the page (owner 2026-11-27 21:57
 * and 22:00), tags, waiting colours, the new-cases pill, the case card over the list, one focus ring (defect 2), and the
 * iPad sizes. Screenshots go to test-results/0108.
 */
const minsAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
const id = (n: number) => `c9c9c9c9-0000-4000-8000-${String(600000 + n).padStart(12, '0')}`;
const named = (base: typeof REVIEW_CASE, loan: string) => base.businessIdentifiers.map((i) => (i.primary ? { ...i, value: loan } : i));
const make = (base: typeof REVIEW_CASE, n: number, over: Record<string, unknown> = {}) => ({
  ...base, packageId: id(n), businessIdentifiers: named(base, `LN-${10000 + n}`), createdAt: minsAgo(200 + n), updatedAt: minsAgo(2), ...over,
});

// 430 cases: 30 for review (one urgent, two waiting long), 120 being redacted, 250 exported today, 10 exported days ago,
// 15 stopped, 5 rejected or cancelled.
const VOLUME = [
  ...Array.from({ length: 30 }, (_, n) => make(REVIEW_CASE, n, n === 0 ? { priority: 'URGENT' } : n === 1 ? { updatedAt: minsAgo(5 * 60) } : n === 2 ? { updatedAt: minsAgo(9 * 60) } : {})),
  ...Array.from({ length: 120 }, (_, n) => make(REDACTING_CASE, 100 + n)),
  ...Array.from({ length: 250 }, (_, n) => make(DELIVERED_CASE, 300 + n)),
  ...Array.from({ length: 10 }, (_, n) => make(DELIVERED_CASE, 600 + n, { updatedAt: minsAgo(3 * 24 * 60) })),
  ...Array.from({ length: 15 }, (_, n) => make(STOPPED_CASE, 700 + n)),
  ...Array.from({ length: 5 }, (_, n) => make(DELIVERED_CASE, 800 + n, { status: n % 2 ? 'REJECTED' : 'CANCELLED' })),
];

// The list answers page by page, as the server does (the page asks 100 at a time).
const paged = (list: unknown[]) => (p: Page) => p.route((url) => url.pathname === '/api/v1/packages', (route) => {
  const url = new URL(route.request().url());
  const offset = Number(url.searchParams.get('offset') ?? 0);
  const limit = Number(url.searchParams.get('limit') ?? 100);
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list.slice(offset, offset + limit)) });
});
const openVolume = (page: Page, who: Who = 'processor', list: unknown[] = VOLUME) => openAt(page, who, '/', paged(list));

const box = (page: Page, label: RegExp) => page.locator('.cf-band .cf-band-seg', { hasText: label });

test('0108: 400+ cases: true counts, Needs you on top and open, the others one line each, Show 25 more', async ({ page }) => {
  await openVolume(page);
  await expect(box(page, /^New/)).toContainText('0');
  await expect(box(page, /^Review/)).toContainText('30');
  await expect(box(page, /^Redacting/)).toContainText('120');
  await expect(box(page, /^Exported/)).toContainText('250');
  await expect(box(page, /^Stopped/)).toContainText('15');
  await expect(page.getByTestId('needs-you-count')).toHaveText('30');

  // Needs you first and open: 25, then 25 more.
  const groups = page.locator('section.cf-group');
  await expect(groups.first()).toHaveAttribute('data-group', 'you');
  const you = page.locator('section[data-group="you"]');
  await expect(you.locator('a.cf-case')).toHaveCount(25);
  await you.getByTestId('show-more-you').click();
  await expect(you.locator('a.cf-case')).toHaveCount(30);

  // In progress and Exported today: one line with the count, no rows until opened.
  const motion = page.locator('section[data-group="motion"]');
  const delivered = page.locator('section[data-group="delivered"]');
  await expect(motion.locator('.cf-group-count')).toHaveText('120');
  await expect(delivered.locator('.cf-group-count')).toHaveText('250');
  await expect(delivered).toContainText('Older cases: Find, or Reports');
  await expect(motion.locator('a.cf-case')).toHaveCount(0);
  await page.getByTestId('group-delivered').click();
  await expect(page.getByTestId('group-delivered')).toHaveAttribute('aria-expanded', 'true');
  await expect(delivered.locator('a.cf-case')).toHaveCount(25);
  await delivered.getByTestId('show-more-delivered').click();
  await expect(delivered.locator('a.cf-case')).toHaveCount(50);

  // Older exported, stopped, rejected and cancelled cases are not listed.
  for (const loan of ['LN-10600', 'LN-10700', 'LN-10800', 'LN-10801']) await expect(page.locator('a.cf-case', { hasText: loan })).toHaveCount(0);
  await page.screenshot({ path: 'test-results/0108/desktop-volume.png', fullPage: false });
});

test('0108: a stage box lists its cases with its sentence; again, or Show all, shows all', async ({ page }) => {
  await openVolume(page);
  await box(page, /^Redacting/).click();
  await expect(box(page, /^Redacting/)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.cf-desk-heading')).toContainText('Redacting');
  await expect(page.getByTestId('stage-count')).toHaveText('120');
  await expect(page.locator('.cf-desk-heading')).toContainText('LexaVault is finding and blacking out sensitive data.');
  await expect(page.locator('a.cf-case').first()).toBeVisible();
  await page.getByRole('button', { name: 'Show all' }).click();
  await expect(page.getByTestId('needs-you-count')).toHaveText('30');
  await box(page, /^Review/).click();
  await box(page, /^Review/).click();
  await expect(page.getByTestId('needs-you-count')).toHaveText('30');
});

test('0108: Stopped counts for everyone and is never a list on Cases', async ({ page }) => {
  await openVolume(page);
  const stopped = box(page, /^Stopped/);
  await expect(stopped).not.toHaveAttribute('aria-pressed', /.*/);
  await expect(stopped).toHaveAttribute('aria-label', /IT works on these in Support/);
});

test('0108: tags after the name, waiting time turns amber then red, rows carry no button', async ({ page }) => {
  await openVolume(page);
  await page.getByTestId('show-more-you').click();
  const urgent = page.locator('li.cf-case-row', { hasText: 'LN-10000' });
  await expect(urgent.locator('.cf-tag[data-tag="urgent"]')).toHaveText('Urgent');
  await expect(page.locator('li.cf-case-row', { hasText: 'LN-10001' }).locator('time')).toHaveAttribute('data-wait', 'late');
  await expect(page.locator('li.cf-case-row', { hasText: 'LN-10002' }).locator('time')).toHaveAttribute('data-wait', 'over');
  await expect(page.locator('li.cf-case-row', { hasText: 'LN-10029' }).locator('time')).not.toHaveAttribute('data-wait', /.*/);
  await expect(page.locator('li.cf-case-row', { hasText: 'LN-10002' })).toContainText('waiting longer than 8 h');
  // No next-step button in any row (the card has the one next step).
  await expect(page.locator('section[data-group="you"] li.cf-case-row button')).toHaveCount(0);
});

test('0108: Send failed tag on a refused export', async ({ page }) => {
  await openAt(page, 'supervisor', '/', undefined, { exports: true });
  const refused = EXPORT_CASES.filter((c) => c.exportState === 'WAITING_FOR_YOU' || c.exportState === 'NEEDS_IT');
  expect(refused.length).toBeGreaterThan(0);
  if (await page.getByTestId('group-motion').getAttribute('aria-expanded') === 'false') await page.getByTestId('group-motion').click();
  for (const c of refused) {
    const loan = c.businessIdentifiers.find((i) => i.primary)!.value;
    await expect(page.locator('li.cf-case-row', { hasText: loan }).locator('.cf-tag[data-tag="failed"]')).toHaveText('Send failed');
  }
});

test('0108: new cases wait behind the pill; Show adds them and highlights them', async ({ page }) => {
  let list: unknown[] = [REVIEW_CASE, APPROVAL_CASE];
  await openAt(page, 'supervisor', '/', (p) => p.route((url) => url.pathname === '/api/v1/packages', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list) })));
  await expect(page.locator('section[data-group="you"] a.cf-case')).toHaveCount(2);
  const newcomer = make(REVIEW_CASE, 900, { createdAt: minsAgo(400) });
  list = [REVIEW_CASE, APPROVAL_CASE, newcomer];
  const pill = page.getByTestId('new-cases-pill');
  await expect(pill).toHaveText('1 new case · Show', { timeout: 15_000 });
  // The count moves at once; the row waits.
  await expect(page.getByTestId('needs-you-count')).toHaveText('3');
  await expect(page.locator('a.cf-case', { hasText: 'LN-10900' })).toHaveCount(0);
  await pill.click();
  const row = page.locator('li.cf-case-row', { hasText: 'LN-10900' });
  await expect(row).toBeVisible();
  await expect(row).toHaveAttribute('data-fresh', 'true');
  await expect(pill).toHaveCount(0);
  // Received, oldest first: the new case (received earliest) is first.
  await expect(page.locator('section[data-group="you"] .cf-case-primary').first()).toHaveText('LN-10900');
  await expect(row).not.toHaveAttribute('data-fresh', /.*/, { timeout: 5_000 });
});

test('0108: the case card slides over the list; nothing moves; another row switches it; Esc or empty space closes it', async ({ page }) => {
  await openVolume(page);
  const list = page.locator('section[data-group="you"] ul.cf-cases');
  const before = await list.boundingBox();
  await page.locator('a.cf-case', { hasText: 'LN-10029' }).click();
  const card = page.locator('aside.cf-drawer');
  await expect(card).toBeVisible();
  const width = (await card.boundingBox())!.width;
  expect(Math.round(width)).toBe(480);
  expect(await list.boundingBox()).toEqual(before);
  await expect(card.locator('.cf-drawer-head')).toHaveCSS('position', 'sticky');
  await expect(page.locator('li.cf-case-row', { hasText: 'LN-10029' })).toHaveAttribute('data-selected', 'true');
  await page.screenshot({ path: 'test-results/0108/desktop-card.png' });

  // The Review button (a case with open findings): full width, no arrow, no "Next case".
  await page.goto(`/case/${REVIEW_CASE.packageId}`);
  const reviewButton = card.getByRole('link', { name: 'Review' });
  await expect(reviewButton).toHaveAttribute('data-wide', '');
  await expect(reviewButton.locator('svg')).toHaveCount(0);
  await expect(card.getByText(/Next case/)).toHaveCount(0);

  await page.locator('a.cf-case', { hasText: 'LN-10028' }).click();
  await expect(page).toHaveURL(new RegExp(`/case/${id(28)}`));
  await page.keyboard.press('Escape');
  await expect(card).toHaveCount(0);

  await page.locator('a.cf-case', { hasText: 'LN-10028' }).click();
  await expect(card).toBeVisible();
  // Empty page space: the gap under the page title.
  await page.mouse.click(20, (await page.locator('.cf-page-head').boundingBox())!.y + 8);
  await expect(card).toHaveCount(0);
});

test('0108 defect 2: the filter box draws one focus ring, on the box', async ({ page }) => {
  await openVolume(page);
  const input = page.locator('.cf-search-cases input');
  await input.focus();
  await expect(input).toHaveCSS('outline-style', 'none');
  const ring = await page.locator('.cf-search-cases').evaluate((el) => getComputedStyle(el).boxShadow);
  expect(ring).not.toBe('none');
});

test('0108: one case in each state: the case card keeps every item it had', async ({ page }) => {
  const cases = [REVIEW_CASE, APPROVAL_CASE, STOPPED_CASE, DELIVERED_CASE, REDACTING_CASE];
  await openAt(page, 'supervisor', '/');
  for (const c of cases) {
    await page.goto(`/case/${c.packageId}`);
    const card = page.locator('aside.cf-drawer');
    await expect(card.locator('ol[aria-label="Stages"]')).toBeVisible();
    await expect(card.locator('section[aria-label="Next step"]')).toBeVisible();
    await expect(card).toContainText('Sensitivity');
    await expect(card).toContainText('Priority');
    await expect(card).toContainText('Documents');
    await expect(card).toContainText('Keeping and deleting');
    await expect(card).toContainText('Case details');
    await card.getByText('Case details').click();
    await expect(card).toContainText('Case ID');
    if (c === STOPPED_CASE) {
      await expect(card.getByTestId('with-it')).toHaveText('With IT');
    }
  }
});

test('0108: each export state: the case card keeps its items', async ({ page }) => {
  await openAt(page, 'supervisor', '/', undefined, { exports: true });
  for (const c of EXPORT_CASES) {
    await page.goto(`/case/${c.packageId}`);
    const card = page.locator('aside.cf-drawer');
    await expect(card.locator('section[aria-label="Next step"], .cf-export-step').first()).toBeVisible();
    await expect(card).toContainText('Keeping and deleting');
    await expect(card).toContainText('Case details');
  }
});

for (const size of [{ name: 'landscape', width: 1180, height: 820, card: 440 }, { name: 'portrait', width: 820, height: 1180, card: 520 }]) {
  test(`0108: iPad ${size.name}`, async ({ page }) => {
    await page.setViewportSize({ width: size.width, height: size.height });
    await openVolume(page);
    const boxes = page.locator('.cf-band .cf-band-seg');
    const tops = new Set(await boxes.evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top))));
    expect(tops.size).toBe(size.name === 'portrait' ? 2 : 1);
    if (size.name === 'portrait') {
      const filter = (await page.locator('.cf-search-cases').boundingBox())!;
      const view = (await page.getByTestId('case-view-button').boundingBox())!;
      expect(filter.y).toBeGreaterThan(view.y + view.height - 1);
      await expect(page.locator('.cf-case-docs').first()).toBeHidden();
    }
    // Nothing scrolls sideways.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.locator('a.cf-case', { hasText: 'LN-10029' }).click();
    const card = (await page.locator('aside.cf-drawer').boundingBox())!;
    expect(Math.round(card.width)).toBe(size.name === 'portrait' ? Math.min(520, size.width - 64) : size.card);
    await page.screenshot({ path: `test-results/0108/ipad-${size.name}.png` });
  });
}
