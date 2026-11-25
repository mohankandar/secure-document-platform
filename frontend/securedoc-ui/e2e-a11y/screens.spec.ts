import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { appendFileSync } from 'node:fs';
import { APPROVAL_CASE, INCIDENT, LICENSING_STOPPED, STOPPED_CASE, go, mockIdentityProvider, mockApi, signInAs } from './harness';

/**
 * UI task U026 A2.1: axe with the WCAG 2.0 and 2.1 A and AA rules on every main screen and state, in light and dark
 * (the two projects in playwright.a11y.config.ts). Each must have 0 violations.
 *
 * Rules turned off: none. (axe's "best-practice" rules, such as "region", are not WCAG criteria and are not run.)
 *
 * A11Y_SURVEY=<file> records every violation to that file (JSON lines) and does not fail; the report's "before"
 * counts come from that file.
 */

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
/** A11Y_SURVEY names the file to write to (outside test-results, which Playwright empties on each run). */
const SURVEY = process.env.A11Y_SURVEY;

async function scan(page: Page, label: string) {
  // Let entry animations and late answers settle, so axe judges the screen people see.
  await page.waitForTimeout(250);
  const result = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const found = result.violations.map((v) => ({
    rule: v.id, impact: v.impact, help: v.help, nodes: v.nodes.map((n) => `${n.target.join(' ')} :: ${n.failureSummary?.replace(/\s+/g, ' ').slice(0, 220)}`),
  }));
  if (SURVEY) {
    appendFileSync(SURVEY, `${JSON.stringify({ screen: label, project: test.info().project.name, count: found.reduce((n, v) => n + v.nodes.length, 0), violations: found })}\n`);
    return;
  }
  expect(found, `axe violations on ${label}`).toEqual([]);
}

test.describe('sign-in', () => {
  test('sign-in page', async ({ page }) => {
    await mockIdentityProvider(page);
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await scan(page, 'Sign-in');
  });

  test('entity chooser', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('heading', { name: 'Choose an entity' })).toBeVisible();
    await scan(page, 'Entity chooser');
  });

  test('no access', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'none');
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible();
    await scan(page, 'No access');
  });
});

test.describe('Cases (Supervisor)', () => {
  test('Cases', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await expect(page.locator('li.cf-case-row').first()).toBeVisible();
    await scan(page, 'Cases');
  });

  test('case panel', async ({ page }) => {
    await signInAs(page, 'supervisor', `/case/${APPROVAL_CASE.packageId}`);
    await expect(page.getByRole('complementary', { name: 'Case panel' })).toBeVisible();
    await scan(page, 'Case panel');
  });

  test('Approve confirm', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.locator('li.cf-case-row').filter({ hasText: 'LN-3002' }).getByRole('button', { name: 'Approve LN-3002' }).click();
    await expect(page.getByRole('dialog', { name: 'Release redacted documents?' })).toBeVisible();
    await scan(page, 'Approve confirm');
  });

  test('account menu', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.getByRole('button', { name: /account menu/i }).click();
    await expect(page.getByRole('menu', { name: 'Account' })).toBeVisible();
    await scan(page, 'Account menu');
  });

  test('find a case or action', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.getByRole('button', { name: 'Find a case or action' }).click();
    await expect(page.getByRole('dialog', { name: 'Find a case or action' })).toBeVisible();
    await scan(page, 'Find a case or action');
  });
});

test.describe('New case, Review, Continue manually (Processor)', () => {
  test('New case', async ({ page }) => {
    await signInAs(page, 'processor', '/intake');
    await expect(page.getByRole('heading', { name: 'New case', level: 1 })).toBeVisible();
    await scan(page, 'New case');
  });

  test('Review: queue, page and decision panel', async ({ page }) => {
    await signInAs(page, 'processor', '/review');
    await expect(page.getByRole('heading', { name: 'Review', level: 1 })).toBeVisible();
    await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('412-55-0193');
    await scan(page, 'Review');
  });

  test('Continue manually', async ({ page }) => {
    await signInAs(page, 'supervisor', `/packages/${STOPPED_CASE.packageId}/manual-processing`);
    await expect(page.locator('main h1').first()).toBeVisible();
    await expect(page.locator('img').first()).toBeVisible();
    await scan(page, 'Continue manually');
  });
});

test.describe('Reports (Business Administrator, Report viewer)', () => {
  test('Reports', async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    await scan(page, 'Reports');
  });

  for (const [name, heading] of [['Daily intake and delivery', 'Received today'], ['Operations', 'Queue']] as const) {
    test(`Reports: ${name}`, async ({ page }) => {
      await signInAs(page, 'business', '/reports');
      await page.getByRole('tab', { name }).click();
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
      await scan(page, `Reports: ${name}`);
    });
  }

  test('Reports: How is this worked out?', async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await page.getByRole('button', { name: 'How is this worked out?' }).click();
    await expect(page.getByRole('dialog', { name: /is worked out/ })).toBeVisible();
    await scan(page, 'Reports: How is this worked out');
  });

  test('Reports: Compliance report dialog', async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await page.getByRole('button', { name: 'Compliance report', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Compliance report' })).toBeVisible();
    await page.getByRole('dialog', { name: 'Compliance report' }).getByRole('button', { name: 'Custom' }).click();
    await scan(page, 'Compliance report dialog');
  });

  test('Reports as Report viewer', async ({ page }) => {
    await signInAs(page, 'viewer', '/reports');
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    await scan(page, 'Reports (Report viewer)');
  });

  test('a problem card (Reports cannot be loaded)', async ({ page }) => {
    await signInAs(page, 'business', '/reports', { broken: ['/operations/reports'] });
    await expect(page.getByRole('button', { name: 'Try again' }).first()).toBeVisible();
    await scan(page, 'Problem card');
  });
});

test.describe('Admin (Business Administrator)', () => {
  test('Redaction & retention', async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    await expect(page.getByRole('region', { name: 'Sensitive data' })).toBeVisible();
    await page.getByText('Fine-tune by hand').click();
    await scan(page, 'Admin: Redaction & retention');
  });

  test('Redaction & retention: preview and the Safe policy confirm step', async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    const card = page.getByRole('region', { name: 'Sensitive data' });
    await card.getByText('Light', { exact: true }).click();
    await expect(card.getByRole('status').locator('.cf-sp-change').first()).toBeVisible();
    await scan(page, 'Admin: policy preview');
    await card.getByRole('button', { name: 'Save Light' }).click();
    await expect(page.getByRole('alertdialog', { name: 'A person would check fewer findings' })).toBeVisible();
    await scan(page, 'Admin: Safe policy confirm');
  });

  test('Case fields and the field editor', async ({ page }) => {
    await signInAs(page, 'business', '/admin/fields');
    await expect(page.getByRole('region', { name: 'Case fields', exact: true }).last()).toBeVisible();
    await scan(page, 'Admin: Case fields');
    await page.getByRole('button', { name: 'Add case field' }).click();
    await expect(page.getByRole('dialog', { name: 'Add case field' })).toBeVisible();
    await scan(page, 'Admin: Add case field');
  });
});

test.describe('Admin (IT Administrator)', () => {
  test('Integrations, each view', async ({ page }) => {
    await signInAs(page, 'it', '/admin/integrations');
    await expect(page.getByRole('region', { name: 'Receiving documents' })).toBeVisible();
    await scan(page, 'Admin: Integrations, Receiving');
    const groups = page.getByRole('group', { name: 'Connection groups' });
    for (const [view, region] of [['Sending to the document store', 'Sending to the document store'], ['Sending by barcode', 'Sending by barcode'], ['All', 'All connections']] as const) {
      await groups.getByRole('button', { name: new RegExp(`^${view} \\d`) }).click();
      await expect(page.getByRole('region', { name: region })).toBeVisible();
      await scan(page, `Admin: Integrations, ${view}`);
    }
  });

  test('Integrations: the connection editor', async ({ page }) => {
    await signInAs(page, 'it', '/admin/integrations');
    await page.getByRole('button', { name: 'Add a connection' }).click();
    await expect(page.getByRole('dialog', { name: 'Add a connection' })).toBeVisible();
    await scan(page, 'Admin: connection editor');
  });

  test('Sign-in', async ({ page }) => {
    await signInAs(page, 'it', '/admin/identity');
    await expect(page.getByRole('region', { name: 'Sign-in in use' })).toBeVisible();
    await scan(page, 'Admin: Sign-in');
  });

  test('Notifications (IT Admin)', async ({ page }) => {
    await signInAs(page, 'it', '/admin/notifications');
    await expect(page.getByRole('region', { name: 'Last messages' })).toBeVisible();
    await scan(page, 'Admin: Notifications (IT)');
  });

  test('Notifications (Business Admin)', async ({ page }) => {
    await signInAs(page, 'business', '/admin/notifications');
    await expect(page.getByRole('table', { name: 'Business messages' })).toBeVisible();
    await scan(page, 'Admin: Notifications (business)');
  });

  test('Approval link', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.goto('/approvals/link/AQEBfake.c2ln');
    await expect(page.getByRole('heading', { name: 'Deliver case LN-3002?' })).toBeVisible();
    await scan(page, 'Approval link');
  });

  test('Audit trail', async ({ page }) => {
    await signInAs(page, 'it', '/admin/audit');
    await expect(page.getByRole('region', { name: 'Audit records' })).toBeVisible();
    await expect(page.locator('main li, main tr').filter({ hasText: /approved|policy/i }).first()).toBeVisible();
    await scan(page, 'Admin: Audit trail');
  });

  test('Platform operations (health overview)', async ({ page }) => {
    await signInAs(page, 'it', '/admin/health');
    await expect(page.getByRole('region', { name: '1 service needs attention' })).toBeVisible();
    await scan(page, 'Admin: Platform (IT)');
  });

  test('Support and the problem page', async ({ page }) => {
    await signInAs(page, 'it', '/support/incidents');
    await expect(page.getByRole('heading', { name: 'Support' })).toBeVisible();
    await expect(page.getByText('LN-3003').first()).toBeVisible();
    await scan(page, 'Support');
    await go(page, `/support/incidents/${INCIDENT}`);
    await expect(page.getByText('LN-3003').first()).toBeVisible();
    await scan(page, 'Problem page');
  });
});

test.describe('Licensing (Business Admin, task 0057)', () => {
  test('Licensing: the licence, the test environment and this month', async ({ page }) => {
    await signInAs(page, 'business', '/admin/licensing');
    await expect(page.getByRole('heading', { name: 'The licence is valid' })).toBeVisible();
    await expect(page.getByText('412 of 1,000 pages')).toBeVisible();
    await scan(page, 'Licensing');
  });
  test('Licensing: intake stopped', async ({ page }) => {
    await signInAs(page, 'business', '/admin/licensing', { licensing: LICENSING_STOPPED });
    await expect(page.getByRole('heading', { name: 'New cases are not taken in' })).toBeVisible();
    await expect(page.getByText('Licence expired · LV-LIC-002')).toBeVisible();
    await scan(page, 'Licensing: intake stopped');
  });
});

test.describe('Sign-in change (IT Admin, task 0058)', () => {
  test('the draft and its dialogs', async ({ page }) => {
    const c = { connectionId: 'c', providerId: 'okta', providerType: 'OIDC', displayName: 'okta', issuerUri: 'https://login.northwind.example', browserClientId: 'web', apiAudience: 'api', status: 'ACTIVE', configurationVersion: 4, matchesSettings: true };
    await signInAs(page, 'it', '/admin/identity', { identity: { active: c, draft: { ...c, connectionId: 'd', status: 'VALIDATED', matchesSettings: false }, previous: null, history: [{ issuerUri: c.issuerUri, version: 4, status: 'ACTIVE', source: 'SETUP', changedBy: 's', changedAt: '2026-11-01T10:00:00Z' }] } });
    await expect(page.getByRole('button', { name: 'Test the draft' })).toBeVisible();
    await page.getByText('Changes so far').click();
    await scan(page, 'Sign-in: draft');
    await page.getByRole('button', { name: 'Discard draft' }).click();
    await scan(page, 'Sign-in: discard');
  });
});

test.describe('Entities (Business Admin, task 0058)', () => {
  test('Entities: the list and the dialogs', async ({ page }) => {
    await signInAs(page, 'business', '/admin/entities');
    await expect(page.getByRole('table', { name: 'Entities you administer' })).toBeVisible();
    await scan(page, 'Entities');
    await page.getByRole('button', { name: 'Activate: ENT-LEX-0003' }).click();
    await scan(page, 'Entities: activate');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Suspend: Commercial lending' }).click();
    await scan(page, 'Entities: suspend');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Time zone: Commercial lending' }).click();
    await scan(page, 'Entities: time zone');
  });
});

test.describe('Support status (task 0057)', () => {
  test('IT Admin: checks, what to do, failures and the bundle', async ({ page }) => {
    await signInAs(page, 'it', '/support/status');
    await expect(page.getByRole('heading', { name: '2 of 4 need attention' })).toBeVisible();
    await expect(page.getByText('R-0J5K2Q7M9X4D')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Download diagnostic bundle' })).toBeVisible();
    await scan(page, 'Support: status');
  });
  test('Platform Operations: no bundle', async ({ page }) => {
    await signInAs(page, 'ops', '/support/status');
    await expect(page.getByRole('heading', { name: '2 of 4 need attention' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Download diagnostic bundle' })).toHaveCount(0);
  });
});

test.describe('Platform operations (Platform Operations)', () => {
  for (const [section, ready] of [
    ['', '1 service needs attention'],
    ['storage', 'Where LexaVault works on documents'],
    ['branding', 'Branding'],
    ['capacity', 'Workers on this server'],
    ['telemetry', 'Usage data'],
  ] as const) {
    test(`Platform: ${section || 'overview'}`, async ({ page }) => {
      await signInAs(page, 'ops', `/admin/health${section ? `?section=${section}` : ''}`);
      await expect(page.getByRole('heading', { name: ready }).first()).toBeVisible();
      await scan(page, `Platform: ${section || 'overview'}`);
    });
  }
});

/* ---------- Task 0029: export screens ---------- */

test.describe('Export screens (task 0029)', () => {
  test('Waiting for you', async ({ page }) => {
    await signInAs(page, 'processor', '/waiting', { exports: true });
    await expect(page.getByTestId('waiting-row').first()).toContainText('LN-');
    await scan(page, 'Waiting for you');
  });

  test('Resend all: confirmation', async ({ page }) => {
    await signInAs(page, 'processor', '/waiting', { exports: true });
    await page.getByRole('button', { name: 'Resend all 3' }).click();
    await expect(page.getByRole('dialog', { name: 'Resend 3 cases to Document vault?' })).toBeVisible();
    await scan(page, 'Resend all: confirmation');
  });

  test('Cases with every export state', async ({ page }) => {
    await signInAs(page, 'supervisor', '/', { exports: true });
    await expect(page.getByTestId('export-status').first()).toBeVisible();
    await scan(page, 'Cases: Exporting');
  });

  for (const [state, n] of [['waiting for you', '7004'], ['ready', '7020'], ['destination paused', '7015'], ['needs IT', '6990']] as const) {
    test(`case panel: Exporting · ${state}`, async ({ page }) => {
      await signInAs(page, 'supervisor', `/case/c9c9c9c9-0000-4000-8000-00000000${n}`, { exports: true });
      await expect(page.getByRole('complementary', { name: 'Case panel' }).getByRole('region', { name: 'Next step' }).locator('.cf-eyebrow')).toHaveText(`Exporting · ${state}`);
      await scan(page, `Case panel: Exporting · ${state}`);
    });
  }

  test('Support: export (with the alert)', async ({ page }) => {
    await signInAs(page, 'it', '/support/export', { exports: true });
    await expect(page.getByTestId('export-destination').first()).toBeVisible();
    await expect(page.getByTestId('it-group').first()).toContainText('LN-6990');
    await scan(page, 'Support: export');
  });

  test('Business Admin: keeping exported copies (out of range)', async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    const card = page.getByRole('region', { name: 'Keeping exported copies' });
    await card.getByLabel('Archive service').fill('80');
    await expect(card).toContainText('from 1 to 72');
    await scan(page, 'Admin: keeping exported copies');
  });
});
