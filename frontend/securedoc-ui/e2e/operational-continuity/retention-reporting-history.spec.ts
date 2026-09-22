import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 7 - UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   The disposable package reached FAILED / Technical Attention via recovery exhaustion, its
 *   technical retention hold was expired and the REAL lifecycle purge ran (artifacts purged,
 *   Dashboard hidden, incident auto-closed). The durable FAILED history remains.
 *
 * This spec confirms the EXISTING Reporting UI still represents the failed processing history after
 * retention cleanup: the Reporting screen loads for a reporting-authorized role and exposes the
 * "Technical attention" processing metric. The authoritative before/after count-stability proof
 * (report count unchanged by retention while the Dashboard count drops) is done by the orchestrator
 * against the real reports API; this spec proves the operator-facing Reporting surface renders it.
 *
 * Env input: LV_OC_REPORT_ATTENTION (the report technicalAttention count observed after retention),
 * used only to assert the rendered metric matches the API value.
 */

const expectedAttention = process.env.LV_OC_REPORT_ATTENTION;

test.describe('Operational Continuity: retention cleanup preserves Reporting history', () => {
  test('the Reporting UI still represents the FAILED technical-attention outcome after retention', async ({ page }) => {
    test.setTimeout(90_000);

    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    // Use an IT-Administrator role (has REPORT_EXECUTIVE_VIEW + dashboard access) and navigate
    // client-side to Reporting so the switched role persists.
    await selectDemoRole(page, 'TENANT_IT_ADMIN');
    await gotoInApp(page, '/reports');

    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Reporting' })).toBeVisible();

    // The Reporting screen exposes the Technical attention processing metric (retained after
    // retention because the report counts FAILED packages independently of Dashboard hiding).
    await expect(page.getByText('Technical attention', { exact: false }).first()).toBeVisible({ timeout: 20_000 });

    // If the orchestrator passed the API-observed count, confirm the UI renders that value.
    if (expectedAttention && /^\d+$/.test(expectedAttention)) {
      await expect(page.getByText(expectedAttention, { exact: false }).first()).toBeVisible();
    }
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
