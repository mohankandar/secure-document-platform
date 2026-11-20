import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { ENTITY, mockApi, mockIdentityProvider, signInAs } from './harness';

/**
 * UI task U027: the proof pictures for the report, saved to ../../docs/work/ui/compare/U027/ (not committed).
 * Run only on request: U027_PROOF=after npx playwright test --config playwright.a11y.config.ts proofU027 --project light
 * (U027_PROOF=before was run once on the code before A1, to take the "before" pictures of the field borders.)
 */

const OUT = '../../docs/work/ui/compare/U027';
mkdirSync(OUT, { recursive: true });
const PHASE = process.env.U027_PROOF === 'before' ? 'before' : 'after';

async function theme(page: Page, t: 'light' | 'dark') {
  await page.evaluate((v) => { document.documentElement.dataset.theme = v; }, t);
  await page.waitForTimeout(250);
}

for (const t of ['light', 'dark'] as const) {
  test(`A1 field borders: New case, ${t}`, async ({ page }) => {
    await signInAs(page, 'processor', '/intake');
    await theme(page, t);
    await expect(page.getByRole('heading', { name: 'New case', level: 1 })).toBeVisible();
    await page.locator('.cf-intake').first().screenshot({ path: `${OUT}/a1-new-case-${t}-${PHASE}.png` });
  });

  test(`A1 field borders: Admin card, ${t}`, async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    await theme(page, t);
    const card = page.getByRole('region', { name: 'Keeping and deleting' });
    await card.scrollIntoViewIfNeeded();
    await card.screenshot({ path: `${OUT}/a1-admin-card-${t}-${PHASE}.png` });
  });

  test(`A1 field borders: Reports filters and Compliance report dialog, ${t}`, async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await theme(page, t);
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    await page.locator('.cf-rp-filters').screenshot({ path: `${OUT}/a1-reports-filters-${t}-${PHASE}.png` });
    await page.getByRole('button', { name: 'Compliance report', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Compliance report' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Custom', exact: true }).click();
    await page.waitForTimeout(250);
    await dialog.screenshot({ path: `${OUT}/a1-compliance-dialog-${t}-${PHASE}.png` });
  });

  test(`A1 field borders: Cases chips and filter, ${t}`, async ({ page }) => {
    await signInAs(page, 'supervisor');
    await theme(page, t);
    await expect(page.locator('li.cf-case-row').first()).toBeVisible();
    await page.locator('.cf-desk-tools').screenshot({ path: `${OUT}/a1-cases-tools-${t}-${PHASE}.png` });
  });

  if (PHASE === 'after') {
    test(`A5 chart with a day focused, ${t}`, async ({ page }) => {
      await signInAs(page, 'business', '/reports');
      await theme(page, t);
      const days = page.getByRole('group', { name: /Received and delivered per day/ });
      await days.focus();
      await page.keyboard.press('End');
      for (let i = 0; i < 4; i += 1) await page.keyboard.press('ArrowLeft');
      await expect(page.getByTestId('trend-tip')).toBeVisible();
      await page.getByTestId('trend-chart').screenshot({ path: `${OUT}/a5-chart-day-focused-${t}.png` });
    });
  }
}

for (const t of ['light', 'dark'] as const) {
  test(`A4 sign-in and entity chooser, ${t}`, async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.emulateMedia({ colorScheme: t });
    await page.goto('/');
    const signIn = page.getByRole('button', { name: /sign in/i });
    await expect(signIn).toBeVisible();
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/a4-sign-in-${t}-${PHASE}.png` });
    await signIn.click();
    await expect(page.getByRole('button', { name: ENTITY.name })).toBeVisible();
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/a4-entity-chooser-${t}-${PHASE}.png` });
  });
}

for (const t of ['light', 'dark'] as const) {
  test(`1024 px: New case, ${t}`, async ({ page }) => {
    test.skip(PHASE !== 'after');
    await page.setViewportSize({ width: 1024, height: 768 });
    await signInAs(page, 'processor', '/intake');
    await theme(page, t);
    await expect(page.getByRole('heading', { name: 'New case', level: 1 })).toBeVisible();
    await page.screenshot({ path: `${OUT}/w1024-new-case-${t}.png`, fullPage: true });
  });

  test(`1024 px: Reports with a chart day focused, ${t}`, async ({ page }) => {
    test.skip(PHASE !== 'after');
    await page.setViewportSize({ width: 1024, height: 768 });
    await signInAs(page, 'business', '/reports');
    await theme(page, t);
    await page.getByRole('group', { name: /Received and delivered per day/ }).focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByTestId('trend-tip')).toBeVisible();
    await page.screenshot({ path: `${OUT}/w1024-reports-${t}.png`, fullPage: true });
  });
}
