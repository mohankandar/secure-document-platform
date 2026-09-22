import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 4 - UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   The disposable package has already reached FAILED / Technical Attention via recovery exhaustion,
 *   leaving an OPEN processing incident.
 *
 * This spec, acting as Tenant IT Admin, drives ONE Support-Incident hold action per invocation,
 * selected by LV_OC_STEP:
 *   - PRESERVE : click "Preserve 7 days"  (PRESERVE_FOR_INVESTIGATION, 7)
 *   - RELEASE  : click "Release hold"     (RELEASE_HOLD)
 *
 * The orchestrator runs this spec twice (PRESERVE, then RELEASE), reading technical_hold_until from
 * the DB between and after the steps. This spec only drives the real UI and confirms the action is
 * acknowledged; the authoritative retention/status/history assertions are done by the orchestrator.
 *
 * Incident id is supplied via LV_OC_INCIDENT_ID. The hold controls require TECHNICAL_HOLD_MANAGE,
 * which the default demo role lacks, so we switch to an IT-Administrator role.
 */

const incidentId = process.env.LV_OC_INCIDENT_ID;
const step = (process.env.LV_OC_STEP || '').toUpperCase();

test.describe('Operational Continuity: Preserve for Investigation / Release Hold', () => {
  test.skip(!incidentId, 'LV_OC_INCIDENT_ID env var is required (set by the orchestrator).');
  test.skip(step !== 'PRESERVE' && step !== 'RELEASE', 'LV_OC_STEP must be PRESERVE or RELEASE.');

  test(`Tenant IT performs the ${step} hold action from the Support Incident view`, async ({ page }) => {
    test.setTimeout(90_000);

    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    await selectDemoRole(page, 'TENANT_IT_ADMIN');
    await gotoInApp(page, `/support/incidents/${incidentId}`);

    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Exceptional IT actions' })).toBeVisible();

    const buttonName = step === 'PRESERVE' ? 'Preserve 7 days' : 'Release hold';
    const actionButton = page.getByRole('button', { name: buttonName });
    await expect(actionButton).toBeVisible();
    await actionButton.click();

    // The action is accepted and the view refreshes.
    await expect(page.getByText('Action accepted', { exact: false })).toBeVisible({ timeout: 30_000 });
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
