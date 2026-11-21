import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { mockApi, mockIdentityProvider } from './harness';

/**
 * Task 0023 proof pictures, saved to ../../docs/work/compare/0023/ (not committed):
 *  - A2: the sign-in screen with the computer set to light and to dark (emulateMedia, as 0022 did for the chooser);
 *  - A4: New case with nothing chosen, showing Standard preselected.
 * Registered only on request, so normal runs and counts are unchanged:
 *   $env:P0023_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0023 --project light
 */
if (process.env.P0023_PROOF) {
  const OUT = '../../docs/work/compare/0023';
  mkdirSync(OUT, { recursive: true });

  for (const scheme of ['light', 'dark'] as const) {
    test(`proof: sign-in screen, ${scheme}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.emulateMedia({ colorScheme: scheme });
      await mockIdentityProvider(page);
      await mockApi(page, 'supervisor');
      await page.goto('/');
      await expect(page.getByRole('button', { name: /sign in/i })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme);
      await page.waitForTimeout(250);
      await page.screenshot({ path: `${OUT}/a2-sign-in-${scheme}-1440.png` });
    });
  }

  test('proof: New case with Standard preselected', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor');
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForLoadState('networkidle');
    await page.goto('/intake');
    const choice = page.getByLabel('Sensitivity');
    await expect(choice).toHaveValue('STANDARD');
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/a4-new-case-standard-preselected-light-1440.png` });
  });
}
