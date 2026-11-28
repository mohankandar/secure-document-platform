import { test, expect, type Page } from '@playwright/test';
import { signInAs } from './harness';

/**
 * UI task U026 A2.3 (WCAG 1.4.10 Reflow): at 320 px wide, Cases, Reports, Approve and the dialogs do not scroll
 * sideways. Review and Continue manually are desktop and tablet screens by the owner's decision (U021) and are
 * recorded as an exception in the report.
 */

const sideways = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

test.use({ viewport: { width: 320, height: 640 } });

test('Cases and the Approve dialog at 320 px', async ({ page }) => {
  await signInAs(page, 'supervisor');
  await expect(page.locator('li.cf-case-row').first()).toBeVisible();
  expect(await sideways(page)).toBeLessThanOrEqual(0);
  // Every workspace link is still reachable.
  for (const name of ['Cases', 'New case', 'Review']) await expect(page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: new RegExp(`^${name}`) })).toBeVisible();
  // Task 0108: the row opens the case card, which takes the whole width at 320 px; its Approve asks inline.
  await page.locator('li.cf-case-row').filter({ hasText: 'LN-3002' }).getByRole('link').click();
  const dialog = page.getByRole('complementary', { name: 'Case panel' });
  await dialog.getByRole('button', { name: 'Approve delivery' }).click();
  await expect(dialog.getByRole('button', { name: 'Confirm' })).toBeVisible();
  const box = (await dialog.boundingBox())!;
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(320);
  expect(await sideways(page)).toBeLessThanOrEqual(0);
});

test('Reports: Daily intake and delivery, and Operations, at 320 px', async ({ page }) => {
  await signInAs(page, 'business', '/reports');
  for (const name of ['Daily intake and delivery', 'Operations']) {
    await page.getByRole('tab', { name }).click();
    await expect(page.locator('.cf-rp-body')).toBeVisible();
    expect(await sideways(page)).toBeLessThanOrEqual(0);
  }
});

test('Reports and the Compliance report dialog at 320 px', async ({ page }) => {
  await signInAs(page, 'business', '/reports');
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  expect(await sideways(page)).toBeLessThanOrEqual(0);
  await page.getByRole('button', { name: 'Compliance report', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Compliance report' });
  await expect(dialog).toBeVisible();
  const box = (await dialog.boundingBox())!;
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(320);
  expect(await sideways(page)).toBeLessThanOrEqual(0);
});
