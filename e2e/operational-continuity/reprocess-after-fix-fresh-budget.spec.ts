import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 3 - UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   The disposable package has already reached FAILED / Technical Attention via recovery exhaustion
 *   (attempt 1->2->3 -> PROCESSING_RECOVERY_EXHAUSTED), leaving an OPEN processing incident.
 *
 * This spec, acting as Tenant IT Admin, opens the REAL Support Incident detail view and invokes the
 * genuine "Reprocess after fix" action. It does NOT call the backend endpoint directly. After the
 * action, it confirms the package leaves the exhausted Technical Attention state (the Support UI
 * refreshes and the incident is no longer OPEN / the failed package is redriven). The orchestrator
 * performs the authoritative DB assertions (new cycle attempt_count = 1, history preserved).
 *
 * The Support Incident detail view is addressed by INCIDENT id, supplied via LV_OC_INCIDENT_ID.
 * The "Reprocess after fix" control requires TECHNICAL_HOLD_MANAGE + TECHNICAL_RETRY; the default
 * demo role (Document Processor) lacks these, so we switch to an IT-Administrator role.
 */

const incidentId = process.env.LV_OC_INCIDENT_ID;

test.describe('Operational Continuity: Reprocess after fix -> fresh budget', () => {
  test.skip(!incidentId, 'LV_OC_INCIDENT_ID env var is required (set by the orchestrator).');

  test('Tenant IT reprocess-after-fix redrives the failed package from the Support Incident view', async ({ page }) => {
    test.setTimeout(90_000);

    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    // Switch to an IT-Administrator role (has TECHNICAL_HOLD_MANAGE + TECHNICAL_RETRY). The LOCAL demo
    // role lives in an in-memory module var that resets on full reload, so navigate client-side.
    await selectDemoRole(page, 'TENANT_IT_ADMIN');
    await gotoInApp(page, `/support/incidents/${incidentId}`);

    // Confirm we are on the Support Incident detail (not the permission-denied fallback).
    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Exceptional IT actions' })).toBeVisible();

    // The failure evidence is visible before the action (durable reason code).
    await expect(page.getByText('PROCESSING_RECOVERY_EXHAUSTED', { exact: false }).first()).toBeVisible();

    // Invoke the genuine "Reprocess after fix" action via the real UI button.
    const reprocessButton = page.getByRole('button', { name: 'Reprocess after fix' });
    await expect(reprocessButton).toBeVisible();
    await reprocessButton.click();

    // The action is accepted and the view refreshes. Once the package is redriven (no longer FAILED)
    // or the incident is no longer OPEN, the "Exceptional IT actions" section is withdrawn.
    // Accept either the explicit accepted-message or the section disappearing.
    const accepted = page.getByText('Action accepted', { exact: false });
    const actionsSection = page.getByRole('heading', { name: 'Exceptional IT actions' });
    await expect
      .poll(async () => {
        const acceptedVisible = await accepted.isVisible().catch(() => false);
        const sectionGone = (await actionsSection.count()) === 0;
        return acceptedVisible || sectionGone;
      }, { timeout: 30_000, message: 'Reprocess action was not acknowledged by the UI' })
      .toBe(true);
  });
});

async function selectDemoRole(page: Page, roleValue: string): Promise<void> {
  const select = page.locator('.demo-role-control select');
  await expect(select).toBeVisible();
  await select.selectOption(roleValue);
  await expect(select).toHaveValue(roleValue);
  await page.waitForTimeout(1_500);
}

/**
 * In-app (client-side) navigation via the History API so the SPA's in-memory demo role is preserved
 * (a full page reload would reset it to the default).
 */
async function gotoInApp(page: Page, path: string): Promise<void> {
  await page.evaluate((p) => {
    window.history.pushState({}, '', p);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.waitForTimeout(500);
}
