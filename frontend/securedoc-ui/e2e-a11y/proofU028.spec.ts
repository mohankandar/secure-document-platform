import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { ENTITY, mockApi, mockIdentityProvider, signInAs } from './harness';

/**
 * UI task U028: the proof pictures for the report, saved to ../../docs/work/ui/compare/U028/ (not committed).
 * Run only on request: U028_PROOF=1 npx playwright test --config playwright.a11y.config.ts proofU028 --project light
 */

const OUT = '../../docs/work/ui/compare/U028';
mkdirSync(OUT, { recursive: true });

async function theme(page: Page, t: 'light' | 'dark') {
  await page.evaluate((v) => { document.documentElement.dataset.theme = v; }, t);
  await page.waitForTimeout(250);
}

for (const width of [1440, 1024]) {
  test(`A1 light edge: Cases filter, ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await signInAs(page, 'supervisor');
    await theme(page, 'light');
    await expect(page.locator('li.cf-case-row').first()).toBeVisible();
    await page.locator('.cf-desk-tools').screenshot({ path: `${OUT}/a1-cases-filter-light-${width}.png` });
  });

  test(`A1 light edge: Compliance period strip, ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await signInAs(page, 'business', '/reports');
    await theme(page, 'light');
    await page.getByRole('button', { name: 'Compliance report', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Compliance report' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Custom', exact: true }).click();
    await page.waitForTimeout(250);
    await dialog.locator('.cf-cr-period').screenshot({ path: `${OUT}/a1-compliance-period-strip-light-${width}.png` });
    await dialog.screenshot({ path: `${OUT}/a1-compliance-dialog-light-${width}.png` });
  });
}

for (const t of ['light', 'dark'] as const) {
  test(`A3 entity chooser words, ${t}`, async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.emulateMedia({ colorScheme: t });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page.getByRole('button', { name: ENTITY.name })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Choose an entity' })).toBeVisible();
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/a3-entity-chooser-${t}.png` });
  });
}
