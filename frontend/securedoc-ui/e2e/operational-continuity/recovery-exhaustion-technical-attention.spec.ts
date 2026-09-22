import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 2 - UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   attempt 1 -> recovery attempt 2 -> recovery attempt 3 -> attempt-3 lease expires
 *   -> package FAILED / Technical Attention, reason PROCESSING_RECOVERY_EXHAUSTED, attempt_count=3, no attempt 4.
 *
 * This spec drives the REAL UI to confirm the disposable package is visible in the
 * Technical Attention operational experience. It performs NO Preserve/Release/Reprocess/Duplicate
 * actions (those are later acceptance tests). Backend DB assertions are done by the orchestrator.
 *
 * The Technical Attention detail surface requires the TECHNICAL_EXCEPTION_VIEW capability, which the
 * default demo role (Document Processor) does not have. In LOCAL security mode the sidebar exposes a
 * demo-role selector; this spec switches to an IT-Administrator role that can view technical issues.
 *
 * Package id is supplied via LV_OC_PACKAGE_ID so the spec is data-driven and rerun-safe.
 */

const packageId = process.env.LV_OC_PACKAGE_ID;

test.describe('Operational Continuity: recovery exhaustion -> Technical Attention', () => {
  test.skip(!packageId, 'LV_OC_PACKAGE_ID env var is required (set by the orchestrator).');

  test('failed package is visible in the Technical Attention experience', async ({ page }) => {
    test.setTimeout(90_000);

    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    // Switch to an IT-Administrator role that can view technical issues. The LOCAL demo role lives in
    // an in-memory module variable that resets on a full page reload, so after switching we must
    // reach the technical-exceptions view via in-app (client-side) navigation, NOT page.goto().
    await selectDemoRole(page, 'TENANT_IT_ADMIN');

    // Navigate client-side to the technical issue detail so the switched role/capabilities persist.
    await gotoInApp(page, `/technical-exceptions/${packageId}`);

    // The page must NOT be the permission-denied fallback.
    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);

    // User-visible Technical Attention state.
    await expect(page.getByRole('heading', { name: 'Technical Issue Details' })).toBeVisible();
    await expect(page.getByText('Technical attention', { exact: false }).first()).toBeVisible();

    // Danger-toned attention badge (StatusBadge ATTENTION_REQUIRED -> tone-danger, label "Attention").
    await expect(page.locator('.ui-status-badge.tone-danger').first()).toBeVisible();

    // The exhaustion reason is surfaced in the structured incident history.
    await expect(page.getByText('PROCESSING_RECOVERY_EXHAUSTED', { exact: false }).first()).toBeVisible();
  });
});

/**
 * Switches the LOCAL demo role via the sidebar selector and waits for the app to re-resolve
 * capabilities for the new role.
 */
async function selectDemoRole(page: Page, roleValue: string): Promise<void> {
  const select = page.locator('.demo-role-control select');
  await expect(select).toBeVisible();
  await select.selectOption(roleValue);
  await expect(select).toHaveValue(roleValue);
  // The app reloads the local principal/capabilities on role change; give it a moment to settle.
  await page.waitForTimeout(1_500);
}

/**
 * Performs an in-app (client-side) navigation using the History API so the SPA's in-memory role
 * state is preserved (a full page reload would reset the demo role to the default).
 */
async function gotoInApp(page: Page, path: string): Promise<void> {
  await page.evaluate((p) => {
    window.history.pushState({}, '', p);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.waitForTimeout(500);
}
