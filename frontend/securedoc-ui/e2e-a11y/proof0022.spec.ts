import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { ENTITY, mockApi, mockIdentityProvider } from './harness';

/**
 * Task 0022 (A4): the entity chooser in light and dark, saved to ../../docs/work/compare/0022/ (not committed). The
 * computer's setting is set with emulateMedia (unlike U028, which set data-theme by hand), so the dark picture shows what
 * a person whose computer is set to dark really sees. Registered only on request:
 *   $env:P0022_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0022 --project light
 */
if (process.env.P0022_PROOF) {
  const OUT = '../../docs/work/compare/0022';
  mkdirSync(OUT, { recursive: true });
  for (const scheme of ['light', 'dark'] as const) {
    test(`proof: entity chooser, ${scheme}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.emulateMedia({ colorScheme: scheme });
      await mockIdentityProvider(page);
      await mockApi(page, 'supervisor', { twoEntities: true });
      await page.goto('/');
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page.getByRole('button', { name: ENTITY.name })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme);
      await page.waitForTimeout(250);
      await page.screenshot({ path: `${OUT}/a4-entity-chooser-${scheme}-1440.png` });
    });
  }
}
