import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { signInAs } from './harness';

/**
 * Task 0030: the proof pictures for the report, saved to ../../docs/work/compare/0030/ (not committed): the Cases list
 * with every Exporting state at 1440 and 1024 px (chips on one line, "Exported"), and Technical details with the
 * destination's own code in the case panel and in Support. The mocked harness: no backend, no secrets, no real data.
 * Registered only on request, so normal runs and counts are unchanged:
 *   $env:P0030_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0030 --project light
 */
if (process.env.P0030_PROOF) {
  const OUT = '../../docs/work/compare/0030';
  mkdirSync(OUT, { recursive: true });
  const settle = async (page: import('@playwright/test').Page) => { await page.waitForTimeout(400); };

  for (const width of [1440, 1024]) {
    test(`Cases list with every Exporting state at ${width} px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await signInAs(page, 'processor', '/', { exports: true });
      await expect(page.locator('.cf-case-stage').filter({ hasText: 'Exporting · destination paused' })).toBeVisible();
      await settle(page);
      await page.screenshot({ path: `${OUT}/1-cases-${width}.png`, fullPage: true });
    });

    test(`Cases list with the case panel open at ${width} px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await signInAs(page, 'processor', '/case/c9c9c9c9-0000-4000-8000-000000007015', { exports: true });
      await expect(page.getByRole('complementary', { name: 'Case panel' })).toContainText('Archive service is paused');
      await settle(page);
      await page.screenshot({ path: `${OUT}/2-cases-with-panel-${width}.png` });
    });
  }

  test('case panel: Technical details with the destination code', async ({ page }) => {
    await signInAs(page, 'processor', '/case/c9c9c9c9-0000-4000-8000-000000007004', { exports: true });
    const panel = page.getByRole('complementary', { name: 'Case panel' });
    await panel.getByText('Technical details').first().click();
    await expect(panel.getByTestId('export-destination-code')).toBeVisible();
    await panel.getByTestId('export-technical').scrollIntoViewIfNeeded();
    await settle(page);
    await panel.screenshot({ path: `${OUT}/3-case-panel-destination-code.png` });
  });

  test('exported case: the stage strip ends with Exported', async ({ page }) => {
    await signInAs(page, 'processor', '/case/c9c9c9c9-0000-4000-8000-000000006985', { exports: true });
    await expect(page.getByRole('complementary', { name: 'Case panel' }).getByRole('list', { name: 'Stages' }).getByRole('listitem').last()).toHaveText('Exported');
    await settle(page);
    await page.screenshot({ path: `${OUT}/4-exported-case.png` });
  });

  test('Support: Technical details with the destination code', async ({ page }) => {
    await signInAs(page, 'it', '/support/export', { exports: true });
    const card = page.getByTestId('it-group').first();
    await card.getByText('Technical details').click();
    await expect(card.getByTestId('it-destination-codes')).toBeVisible();
    await settle(page);
    await card.screenshot({ path: `${OUT}/5-support-destination-code.png` });
  });
}
