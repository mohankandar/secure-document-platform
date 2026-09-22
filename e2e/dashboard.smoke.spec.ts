import { test, expect } from '@playwright/test';

/**
 * Smoke test only: verifies the LexaVault application shell + Dashboard load.
 * No workflow mutation. Uses existing local/demo authentication behavior
 * (LOCAL security mode renders the authenticated shell without OIDC sign-in).
 */
test('application shell and dashboard load', async ({ page }) => {
  await page.goto('/');

  // The authenticated application shell renders in local/demo mode without a sign-in step.
  const shell = page.locator('[data-ui-design-system="1"].modern-app-shell');
  await expect(shell).toBeVisible();

  // The Dashboard navigation entry is present in the primary navigation.
  await expect(page.getByRole('link', { name: 'Dashboard' })).toBeVisible();

  // The Dashboard route is the default view; confirm the main content region rendered.
  await expect(page.locator('main.modern-main-content')).toBeVisible();
});
