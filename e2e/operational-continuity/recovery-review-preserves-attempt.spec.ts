import { test, expect, type Page } from '@playwright/test';

/**
 * Operational Continuity Acceptance Test 1 — UI portion.
 *
 * Scenario context (prepared by the PowerShell orchestrator before this spec runs):
 *   attempt 1  ->  real expired-lease/node-loss recovery  ->  attempt 2  ->  PENDING_REVIEW
 *
 * This spec drives the REAL Manual Review UI to resolve the outstanding review item(s) for the
 * disposable package, then waits for the pipeline to resume. The orchestrator performs the hidden
 * DB assertions (attempt_count must remain 2 before review, after resume, and at completion).
 *
 * The package id is supplied via the LV_OC_PACKAGE_ID environment variable so this spec is
 * data-driven and safe to re-run for any disposable package.
 *
 * Selectors intentionally match the shipped ReviewPage component:
 *   - .review-dismiss        -> "Not Sensitive"
 *   - .review-dismiss-final  -> "Yes, not sensitive"
 *   - .success-msg           -> success banner ("...processing has resumed...")
 */

const packageId = process.env.LV_OC_PACKAGE_ID;

test.describe('Operational Continuity: recovery + manual review preserves attempt_count', () => {
  test.skip(!packageId, 'LV_OC_PACKAGE_ID env var is required (set by the orchestrator).');

  test('completes the real Manual Review UI and resumes processing', async ({ page }) => {
    // Manual review can involve rendering PDF page previews; allow a generous bounded budget.
    test.setTimeout(120_000);

    await page.goto(`/review?packageId=${packageId}`);

    // Confirm we are on the authenticated modern shell (LOCAL security mode, no login).
    await expect(page.locator('[data-ui-design-system="1"].modern-app-shell')).toBeVisible();

    // The Manual Review workbench should show at least one item needing attention for this package.
    await expect(page.getByRole('heading', { name: 'Manual Review' })).toBeVisible();

    await resolveAllReviewItems(page);

    // Once the last review item is resolved, the UI reports that processing resumed.
    const banner = page.locator('.success-msg');
    await expect(banner).toBeVisible({ timeout: 30_000 });
    await expect(banner).toContainText(/processing has resumed/i);
  });
});

/**
 * Dismisses every outstanding review item via the real UI until the "all caught up" empty state
 * appears. Uses "Not Sensitive" -> "Yes, not sensitive" because it requires no bounding-box drawing
 * and deterministically resolves an item. Resolving the final item is what triggers pipeline resume.
 */
async function resolveAllReviewItems(page: Page): Promise<void> {
  const maxItems = 20; // safety bound; the credit-score fixture yields very few items.

  for (let i = 0; i < maxItems; i++) {
    const emptyState = page.locator('.review-empty');
    const dismissButton = page.locator('button.review-dismiss').first();

    // Stop once the queue is empty.
    if (await emptyState.isVisible().catch(() => false)) {
      return;
    }

    await expect(dismissButton).toBeVisible({ timeout: 30_000 });
    await dismissButton.click();

    const confirmDismiss = page.locator('button.review-dismiss-final').first();
    await expect(confirmDismiss).toBeVisible();
    await confirmDismiss.click();

    // Wait for the resolution to register: either the success banner appears (last item) or the
    // queue re-renders with one fewer item. A short settle wait keeps this stable without polling DB.
    await page.waitForTimeout(1_500);

    if (await page.locator('.success-msg').isVisible().catch(() => false)) {
      return;
    }
  }

  throw new Error(`Exceeded ${maxItems} review items without resolving the queue.`);
}
