import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 5 - UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   The disposable package reached FAILED / Technical Attention via recovery exhaustion, its
 *   technical retention hold was then expired and the REAL lifecycle purge ran (artifacts purged,
 *   Dashboard hidden, incident auto-closed). Durable package/incident/audit history remains.
 *
 * This spec confirms the user-visible consequences:
 *   1. Dashboard: the package no longer appears operationally (searching its unique primary
 *      identifier yields no matching intake).
 *   2. Support history: the previous failure/incident is still discoverable via the Support Incident
 *      detail view (history is retained after retention cleanup).
 *
 * Env inputs: LV_OC_CONTRACT (unique primary identifier), LV_OC_INCIDENT_ID (support incident id).
 */

const contract = process.env.LV_OC_CONTRACT;
const incidentId = process.env.LV_OC_INCIDENT_ID;

test.describe('Operational Continuity: Technical Attention retention -> hidden operationally, history durable', () => {
  test.skip(!contract || !incidentId, 'LV_OC_CONTRACT and LV_OC_INCIDENT_ID are required (set by the orchestrator).');

  test('purged package is gone from the Dashboard but still discoverable in Support history', async ({ page }) => {
    test.setTimeout(90_000);

    // --- Dashboard: package must no longer appear operationally ---
    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    const searchBox = page.getByLabel('Search configured business identifiers');
    await expect(searchBox).toBeVisible();
    await searchBox.fill(contract as string);

    // Debounced search (300ms) + server round-trip; the retained-but-hidden package must not appear.
    // The unique contract number should yield the empty state, and must never render as a row.
    await expect(page.getByRole('heading', { name: 'No matching intakes' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(contract as string, { exact: false })).toHaveCount(0);

    // --- Support history: the incident is still discoverable after retention cleanup ---
    await selectDemoRole(page, 'TENANT_IT_ADMIN');
    await gotoInApp(page, `/support/incidents/${incidentId}`);

    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);
    // The durable failure reason remains visible in the historical support record.
    await expect(page.getByText('PROCESSING_RECOVERY_EXHAUSTED', { exact: false }).first()).toBeVisible();
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
