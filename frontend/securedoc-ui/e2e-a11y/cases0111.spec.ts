import { test, expect } from '@playwright/test';
import { DELIVERED_CASE, REVIEW_CASE } from './harness';
import { answer, openAt } from './states0089';

/**
 * Task 0111 items 1, 2 and 7 (owner's tests): every case counts and can be shown (25 per group, then "Show more"), a
 * cancelled case says Cancelled (not Unrecognized), and a case row opens its panel every time it is clicked.
 */
const id = (n: number) => `c9c9c9c9-0000-4000-8000-${String(9000 + n).padStart(12, '0')}`;
const delivered = Array.from({ length: 30 }, (_, n) => ({
  ...DELIVERED_CASE, packageId: id(n),
  businessIdentifiers: DELIVERED_CASE.businessIdentifiers.map((i) => (i.primary ? { ...i, value: `LN-${5000 + n}` } : i)),
}));
const CANCELLED = { ...DELIVERED_CASE, packageId: id(99), status: 'CANCELLED',
  businessIdentifiers: DELIVERED_CASE.businessIdentifiers.map((i) => (i.primary ? { ...i, value: 'LN-5999' } : i)) };
const LIST = [REVIEW_CASE, DELIVERED_CASE, CANCELLED, ...delivered];

const open = (page: import('@playwright/test').Page) =>
  openAt(page, 'processor', '/', async (p) => {
    await answer(p, '/packages', LIST);
    await answer(p, `/packages/${CANCELLED.packageId}`, { ...CANCELLED, documents: [] });
  });

test('0111: true counts per group, 25 shown, then Show more', async ({ page }) => {
  await open(page);
  const group = page.locator('section[data-group="delivered"]');
  // Task 0108: Exported today is one line with its true count until opened.
  await expect(group.locator('.cf-group-count')).toHaveText('31');
  await expect(group.locator('a.cf-case')).toHaveCount(0);
  await page.getByTestId('group-delivered').click();
  await expect(group.locator('a.cf-case')).toHaveCount(25);
  await group.getByTestId('show-more-delivered').click();
  await expect(group.locator('a.cf-case')).toHaveCount(31);
  await expect(group.getByTestId('show-more-delivered')).toHaveCount(0);
});

test('0111: a cancelled case says Cancelled (0108: final, so not listed; opened from Find)', async ({ page }) => {
  await open(page);
  await page.getByTestId('group-delivered').click();
  await expect(page.locator('a.cf-case', { hasText: 'LN-5999' })).toHaveCount(0);
  await page.goto(`/case/${CANCELLED.packageId}`);
  await expect(page.locator('.cf-drawer')).toContainText('Cancelled');
  await expect(page.getByText('Unrecognized')).toHaveCount(0);
});

test('0111 U8: a case row opens its panel every time it is clicked', async ({ page }) => {
  await open(page);
  const review = page.locator('a.cf-case', { hasText: 'LN-3001' });
  const other = page.locator('a.cf-case', { hasText: 'LN-3004' });
  const close = page.getByRole('button', { name: 'Close case' });
  await page.getByTestId('group-delivered').click();
  for (let round = 0; round < 3; round += 1) {
    await review.click();
    await expect(close).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/case/${REVIEW_CASE.packageId}`));
    await close.click();
    await expect(close).toHaveCount(0);
  }
  await review.click();
  await other.click();
  await expect(page).toHaveURL(new RegExp(`/case/${DELIVERED_CASE.packageId}`));
  await review.click();
  await expect(page).toHaveURL(new RegExp(`/case/${REVIEW_CASE.packageId}`));
  await expect(close).toBeVisible();
});
