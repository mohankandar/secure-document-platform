import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 6 - UI portion.
 *
 * Drives ONE terminal Tenant-IT disposition per invocation on a FAILED / Technical Attention
 * package's Support Incident, selected by LV_OC_ACTION:
 *   - RESOLVE_NO_REPROCESS : "Resolve - no reprocess"
 *   - SUPERSEDED           : "Mark superseded"
 *   - DUPLICATE            : "Mark duplicate"
 *
 * The orchestrator runs this spec once per scenario (each on its own fresh exhausted package) and
 * performs the authoritative DB assertions. This spec only drives the real UI and confirms the
 * action is acknowledged. Incident id via LV_OC_INCIDENT_ID.
 *
 * The disposition controls require TECHNICAL_HOLD_MANAGE (+ TECHNICAL_RETRY for reprocess only),
 * which the default demo role lacks, so we switch to an IT-Administrator role.
 */

const incidentId = process.env.LV_OC_INCIDENT_ID;
const action = (process.env.LV_OC_ACTION || '').toUpperCase();

// Accessible button names use regex to avoid punctuation/dash ambiguity (e.g. the em-dash in
// "Resolve — no reprocess").
const BUTTON_NAME: Record<string, RegExp> = {
  RESOLVE_NO_REPROCESS: /Resolve.*no reprocess/i,
  SUPERSEDED: /Mark superseded/i,
  DUPLICATE: /Mark duplicate/i,
};

test.describe('Operational Continuity: Tenant IT terminal dispositions', () => {
  test.skip(!incidentId, 'LV_OC_INCIDENT_ID env var is required (set by the orchestrator).');
  test.skip(!BUTTON_NAME[action], 'LV_OC_ACTION must be RESOLVE_NO_REPROCESS, SUPERSEDED, or DUPLICATE.');

  test(`Tenant IT performs the ${action} disposition from the Support Incident view`, async ({ page }) => {
    test.setTimeout(90_000);

    await page.goto('/');
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    await selectDemoRole(page, 'TENANT_IT_ADMIN');
    await gotoInApp(page, `/support/incidents/${incidentId}`);

    await expect(page.getByRole('heading', { name: 'Permission required' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Exceptional IT actions' })).toBeVisible();

    // The durable failure reason is visible before the disposition.
    await expect(page.getByText('PROCESSING_RECOVERY_EXHAUSTED', { exact: false }).first()).toBeVisible();

    const actionButton = page.getByRole('button', { name: BUTTON_NAME[action] });
    await expect(actionButton).toBeVisible();
    await actionButton.click();

    // A terminal disposition resolves the incident. The view refreshes and the incident status
    // becomes "Resolved". The "Exceptional IT actions" section (shown only while the incident is
    // still active) is then withdrawn - so the transient "Action accepted" banner is not a reliable
    // signal. Assert the durable resolved state instead: the incident summary shows Resolved and the
    // "Exceptional IT actions" heading is gone.
    await expect
      .poll(async () => {
        const resolvedVisible = await page.getByText('Resolved', { exact: false }).first().isVisible().catch(() => false);
        const actionsGone = (await page.getByRole('heading', { name: 'Exceptional IT actions' }).count()) === 0;
        return resolvedVisible && actionsGone;
      }, { timeout: 30_000, message: 'Incident did not transition to a resolved disposition state' })
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
