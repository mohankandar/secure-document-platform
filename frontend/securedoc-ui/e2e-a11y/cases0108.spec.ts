import { test, expect, type Page } from '@playwright/test';
import { DELIVERED_CASE, REVIEW_CASE } from './harness';
import { answer, openAt } from './states0089';

/**
 * Task 0108 (Cases at volume), part 1: the View menu. Sort re-orders the cases inside a group and comes back after the
 * page is opened again (the choice is kept by the server, here a stand-in that keeps what was PUT); density; Columns
 * offers only what the server says the entity offers.
 */
const id = (n: number) => `c9c9c9c9-0000-4000-8000-${String(8000 + n).padStart(12, '0')}`;
const minsAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
// Three delivered cases: received in the order B, C, A; last changed in the order A, B, C (all today: only today's
// exported cases are listed, task 0108).
const delivered = [
  { loan: 'LN-7002', received: 300, changed: 3 },
  { loan: 'LN-7003', received: 200, changed: 2 },
  { loan: 'LN-7001', received: 100, changed: 9 },
].map((c, n) => ({
  ...DELIVERED_CASE, packageId: id(n), createdAt: minsAgo(c.received), updatedAt: minsAgo(c.changed),
  businessIdentifiers: DELIVERED_CASE.businessIdentifiers.map((i) => (i.primary ? { ...i, value: c.loan } : i)),
}));
const OPTIONS = {
  sorts: ['received', 'waitingLongest', 'priority', 'field:loan_number'],
  columns: ['loan_number', 'borrower_name', 'received', 'uploaded', 'source'],
};

async function open(page: Page, saved: { settings: Record<string, unknown> }, puts: unknown[]) {
  await openAt(page, 'processor', '/', async (p) => {
    await answer(p, '/packages', [REVIEW_CASE, ...delivered]);
    await p.route((url) => url.pathname === '/api/v1/me/case-view', async (route) => {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON();
        puts.push(body);
        saved.settings = body;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ settings: saved.settings, options: OPTIONS }) });
    });
  });
}

// Exported today starts as one line (approved design): open it, then read its rows.
const order = async (page: Page) => {
  const line = page.getByTestId('group-delivered');
  if ((await line.getAttribute('aria-expanded')) === 'false') await line.click();
  return page.locator('section[data-group="delivered"] .cf-case-primary').allTextContents();
};

test('0108: sort re-orders inside a group and is kept on the server', async ({ page }) => {
  const saved = { settings: { sort: 'received', order: 'asc', columns: null, density: 'comfortable' } as Record<string, unknown> };
  const puts: unknown[] = [];
  await open(page, saved, puts);
  await expect.poll(() => order(page)).toEqual(['LN-7002', 'LN-7003', 'LN-7001']);
  await expect(page.getByTestId('sorted-you')).toHaveText('sorted by Received, oldest first');

  await page.getByTestId('case-view-button').click();
  const menu = page.getByTestId('case-view-menu');
  await menu.getByLabel('Loan number').first().check();
  await expect.poll(() => order(page)).toEqual(['LN-7001', 'LN-7002', 'LN-7003']);
  await menu.getByLabel('High → low').check();
  await expect.poll(() => order(page)).toEqual(['LN-7003', 'LN-7002', 'LN-7001']);
  await menu.getByLabel('Waiting longest').check();
  await expect.poll(() => order(page)).toEqual(['LN-7001', 'LN-7002', 'LN-7003']);
  expect(puts.at(-1)).toMatchObject({ sort: 'waitingLongest', order: 'asc' });

  // Opened again (as after signing out and in): the server's copy decides.
  await page.reload();
  await page.locator('#cf-main h1').first().waitFor();
  await expect.poll(() => order(page)).toEqual(['LN-7001', 'LN-7002', 'LN-7003']);
  await expect(page.getByTestId('sorted-you')).toHaveText('sorted by Waiting longest, oldest first');
});

test('0108: density and columns', async ({ page }) => {
  const saved = { settings: { sort: 'received', order: 'asc', columns: null, density: 'comfortable' } as Record<string, unknown> };
  const puts: unknown[] = [];
  await open(page, saved, puts);
  await order(page);
  const desk = page.locator('.cf-desk');
  await expect(desk).toHaveAttribute('data-density', 'comfortable');
  await page.getByTestId('case-view-button').click();
  const menu = page.getByTestId('case-view-menu');
  await menu.getByLabel('Compact').check();
  await expect(desk).toHaveAttribute('data-density', 'compact');

  // Columns: exactly the ones the entity offers, nothing else.
  const columns = menu.locator('fieldset', { has: page.locator('legend', { hasText: 'Columns' }) }).locator('label');
  await expect(columns).toHaveText(['Loan number', 'Borrower name', 'Received', 'Uploaded', 'Source']);
  const row = page.locator('a.cf-case', { hasText: 'LN-7001' });
  await expect(row).toContainText('Daniel Kim');
  await menu.getByLabel('Borrower name').uncheck();
  await expect(row).not.toContainText('Daniel Kim');
  expect(puts.at(-1)).toMatchObject({ density: 'compact', columns: ['loan_number', 'received', 'source'] });

  // Esc closes the menu and gives focus back to its button.
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(page.getByTestId('case-view-button')).toBeFocused();
});

test('0108: a view that cannot be loaded leaves the default order', async ({ page }) => {
  await openAt(page, 'processor', '/', async (p) => {
    await answer(p, '/packages', [REVIEW_CASE, ...delivered]);
    await answer(p, '/me/case-view', { message: 'Something went wrong', code: 'LV-SYS-006' }, 500);
  });
  await expect.poll(() => order(page)).toEqual(['LN-7002', 'LN-7003', 'LN-7001']);
});
