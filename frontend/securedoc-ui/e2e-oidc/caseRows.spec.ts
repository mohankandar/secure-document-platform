import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U003: the Cases rows. The row's action button does its own job and never also opens the case panel; the
 * rest of the row still opens it; Approve asks for the same confirmation as the case panel.
 *
 * Same harness as errorStates.spec.ts: the real app on the Vite dev server (OIDC mode, Case File design on), with the
 * identity provider (idp.test) and the API mocked. No backend is needed.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c3c3c3c3-0000-4000-8000-00000000000c', name: 'Rows Entity', slug: 'rows-entity', roles: ['DOCUMENT_SUPERVISOR'] };

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'error-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;

async function mockIdentityProvider(page: Page) {
  await page.route(`${ISSUER}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        issuer: ISSUER, authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
        token_endpoint: `${ISSUER}/protocol/openid-connect/token`, end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
      }) });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/auth')) {
      const back = new URL(url.searchParams.get('redirect_uri') || '/');
      back.searchParams.set('code', 'error-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'error-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

/** `broken` holds the API paths that currently answer with a shape no screen understands. */

const SUPERVISOR = capabilitiesFor(...ENTITY.roles);
const REVIEW_CASE = caseFor('LN-2001', 'PENDING_REVIEW', '0001');
const APPROVAL_CASE = caseFor('LN-2002', 'PENDING_FINAL_APPROVAL', '0002');

function caseFor(loan: string, status: string, n: string) {
  return {
    packageId: `c3c3c3c3-0000-4000-8000-00000000${n}`, tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status,
    documentCount: 1, createdAt: '2026-11-17T12:00:00Z', updatedAt: '2026-11-17T12:00:00Z', documents: [],
    businessIdentifiers: [{ key: 'loan_number', label: 'Loan number', value: loan, primary: true, searchable: true, displayable: true, displayOrder: 1 }],
  };
}

async function mockApi(page: Page, approvals: string[], extra: object[] = []) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/v1/me/tenants') return json([ENTITY]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'rows.user', roles: ENTITY.roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: SUPERVISOR });
    }
    if (url.pathname === '/api/v1/packages') return json([REVIEW_CASE, APPROVAL_CASE, ...extra]);
    const one = [REVIEW_CASE, APPROVAL_CASE].find((c) => url.pathname === `/api/v1/packages/${c.packageId}`);
    if (one) return json(one);
    if (url.pathname.endsWith('/approve-delivery') && route.request().method() === 'POST') {
      approvals.push(url.pathname);
      return json({ packageId: APPROVAL_CASE.packageId, status: 'COMPLETED' });
    }
    if (url.pathname.startsWith('/api/v1/review-items')) {
      return json({ totalItems: 2, items: ['f1', 'f2'].map((id) => ({ findingId: id, packageId: REVIEW_CASE.packageId, documentId: 'd1', reviewTaskId: 't1',
        entityType: 'SSN', pageNumber: 1, confidence: 0.6, reason: 'LOW_CONFIDENCE', hasBoundingBox: true })) });
    }
    if (url.pathname.includes('/lifecycle')) return json({ packageId: '', successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: false, finalApprovalRequired: true, finalApprovalGranted: false });
    return json({});
  });
}

async function signIn(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
}

const row = (page: Page, loan: string) => page.locator('li.cf-case-row').filter({ hasText: loan });

test('each row shows the next step in words and only the action this person can take', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await expect(row(page, 'LN-2001')).toContainText('2 findings to review');
  await expect(row(page, 'LN-2001').getByRole('button', { name: 'Review LN-2001' })).toBeVisible();
  await expect(row(page, 'LN-2002')).toContainText('Redaction done, ready to approve');
  await expect(row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' })).toBeVisible();
});

test('the Review button opens Review for the case, not the case panel', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await row(page, 'LN-2001').getByRole('button', { name: 'Review LN-2001' }).click();
  await expect(page).toHaveURL((url) => url.pathname === '/review' && url.searchParams.get('case') === REVIEW_CASE.packageId);
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
});

test('Approve asks for the same confirmation as the case panel, approves, and does not open the panel', async ({ page }) => {
  const approvals: string[] = [];
  await mockIdentityProvider(page);
  await mockApi(page, approvals);
  await signIn(page);
  await row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' }).click();
  const dialog = page.getByRole('dialog', { name: 'Release redacted documents?' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('cannot be recalled from the destination');
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(approvals).toHaveLength(0);

  await row(page, 'LN-2002').getByRole('button', { name: 'Approve LN-2002' }).click();
  await page.getByRole('dialog', { name: 'Release redacted documents?' }).getByRole('button', { name: 'Approve delivery' }).click();
  await expect.poll(() => approvals.length).toBe(1);
  await expect(page.getByRole('complementary', { name: 'Case panel' })).toHaveCount(0);
});

test('clicking the rest of the row still opens the case panel', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await signIn(page);
  await row(page, 'LN-2002').getByRole('link').click();
  const panel = page.getByRole('complementary', { name: 'Case panel' });
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Approve delivery' })).toBeVisible();
});

// Task 0038: a stopped, a waiting and a rejected case, and one delivered to a named destination.
const STOPPED_CASE = { ...caseFor('LN-3001', 'FAILED', '0031'), stopReasonCode: 'LV-PRC-004' };
const WAITING_CASE = { ...caseFor('LN-3002', 'COMPLETED', '0032'), exportState: 'NEEDS_IT', stopReasonCode: 'LV-DLV-004', destinationName: 'Records archive' };
const REJECTED_CASE = { ...caseFor('LN-3003', 'REJECTED', '0033'), stopReasonCode: null };
const SENT_CASE = { ...caseFor('LN-3004', 'COMPLETED', '0034'), exportState: 'EXPORTED', destinationName: 'Records archive' };
const ODD_CASE = { ...caseFor('LN-3005', 'FAILED', '0035'), stopReasonCode: 'Jane Doe 123-45-6789' };

test('task 0038: a stopped, waiting or rejected row says why in plain words, and where the case goes', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [], [STOPPED_CASE, WAITING_CASE, REJECTED_CASE, SENT_CASE, ODD_CASE]);
  await signIn(page);
  await expect(row(page, 'LN-3001').locator('.cf-case-why')).toHaveText('Text reading timed out');
  await expect(row(page, 'LN-3002').locator('.cf-case-why')).toHaveText('Destination refused the file');
  await expect(row(page, 'LN-3002').locator('.cf-case-to')).toHaveText('To Records archive');
  await expect(row(page, 'LN-3003')).toContainText('Rejected, nothing more to do');
  await expect(row(page, 'LN-3003').locator('.cf-case-why')).toHaveCount(0); // no rejection reason is stored yet
  await expect(row(page, 'LN-3004').locator('.cf-case-why')).toHaveCount(0);
  await expect(row(page, 'LN-3004').locator('.cf-case-to')).toHaveText('To Records archive');
  await expect(row(page, 'LN-3005').locator('.cf-case-why')).toHaveCount(0); // never text that is no known code
  await expect(page.locator('.cf-desk')).not.toContainText('Jane Doe');
  await expect(row(page, 'LN-2001').locator('.cf-case-why, .cf-case-to')).toHaveCount(0);
  if (process.env.P0038_PROOF) {
    await page.screenshot({ path: '../../docs/work/0038-proof/cases-stopped-waiting-rejected.png', fullPage: true });
  }
});

test('task 0071: Reject case in the case panel: the dialog names the case and the reason, and sends only the code', async ({ page }) => {
  const sent: { path: string; body: unknown }[] = [];
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await page.route('**/api/v1/packages/*/*/reject', async (route) => {
    sent.push({ path: new URL(route.request().url()).pathname, body: JSON.parse(route.request().postData() || '{}') });
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ packageId: '', status: 'REJECTED', reasonCode: 'LV-REV-013' }) });
  });
  await signIn(page);
  for (const [loan, step, kase] of [['LN-2001', 'review', REVIEW_CASE], ['LN-2002', 'approval', APPROVAL_CASE]] as const) {
    await row(page, loan).getByRole('link').click();
    const panel = page.getByRole('complementary', { name: 'Case panel' });
    await panel.getByRole('button', { name: 'Reject case' }).click();
    const dialog = page.getByRole('dialog', { name: `Reject case ${loan}?` });
    await expect(dialog).toContainText('This can’t be undone');
    await expect(dialog.getByRole('button', { name: 'Reject case' })).toBeDisabled();
    await expect(dialog.getByLabel('Reason').getByRole('option')).toHaveText(['Choose a reason', 'Wrong document', 'Unreadable', 'Out of scope', 'Duplicate', 'Other (see ticket)']);
    await dialog.getByLabel('Reason').selectOption('LV-REV-013');
    await expect(dialog).toContainText('Duplicate');
    if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/reject-${step}.png` });
    await dialog.getByRole('button', { name: 'Reject case' }).click();
    await expect(page.getByText('Case rejected.').first()).toBeVisible();
    expect(sent.at(-1)).toEqual({ path: `/api/v1/packages/${kase.packageId}/${step}/reject`, body: { reasonCode: 'LV-REV-013' } });
    await page.getByRole('button', { name: 'Close case' }).click();
  }
  expect(sent).toHaveLength(2);
});

test('task 0071: Review has Reject case, and the Cases list shows the reason', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, [], [{ ...caseFor('LN-4001', 'REJECTED', '0041'), stopReasonCode: 'LV-REV-010' }]);
  await signIn(page);
  await expect(row(page, 'LN-4001')).toContainText('Rejected: wrong document');
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/reject-list.png` });
  await row(page, 'LN-2001').getByRole('button', { name: 'Review LN-2001' }).click();
  await page.getByRole('button', { name: 'Reject case' }).click();
  await expect(page.getByRole('dialog', { name: 'Reject case LN-2001?' })).toBeVisible();
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/reject-in-review.png` });
});

test('task 0076: bulk approval: own cases are greyed with the reason, the confirmation lists the cases, and one that fails stays with its reason', async ({ page }) => {
  const sent: string[][] = [];
  const second = caseFor('LN-5002', 'PENDING_FINAL_APPROVAL', '0052');
  const own = caseFor('LN-5003', 'PENDING_FINAL_APPROVAL', '0053');
  await mockIdentityProvider(page);
  await mockApi(page, [], [second, own]);
  await page.route('**/api/v1/bulk-approval/offer', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ enabled: true, maximum: 50, reviewedByYou: [own.packageId] }) }));
  await page.route('**/api/v1/bulk-approval', (route) => {
    const ids = JSON.parse(route.request().postData() || '{}').packageIds as string[];
    sent.push(ids);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ids.map((id) => ({ packageId: id, approved: id !== second.packageId, reasonCode: id === second.packageId ? 'LV-REV-002' : null }))) });
  });
  await signIn(page);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await page.getByRole('group', { name: 'Cases by lifecycle stage' }).getByRole('button', { name: /^Approval/ }).click();
  const approve = page.getByRole('button', { name: /^Approve selected/ });
  await expect(approve).toBeDisabled();
  await expect(page.getByRole('checkbox', { name: 'Select LN-5003' })).toBeDisabled();
  await expect(row(page, 'LN-5003')).toContainText('You reviewed this case, so another supervisor approves it.');
  await page.getByRole('checkbox', { name: 'Select LN-2002' }).check();
  await page.getByRole('checkbox', { name: 'Select LN-5002' }).check();
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/bulk-selected.png` });
  await expect(approve).toHaveText('Approve selected (2)');
  await approve.click();
  const dialog = page.getByRole('dialog', { name: 'Approve 2 cases for delivery?' });
  await expect(dialog).toContainText('LN-2002');
  await expect(dialog).toContainText('LN-5002');
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/bulk-confirm.png` });
  await dialog.getByRole('button', { name: 'Approve 2' }).click();
  await expect(page.getByRole('status').filter({ hasText: '1 case approved.' })).toContainText('LN-5002:');
  if (process.env.PROOF_DIR) await page.screenshot({ path: `${process.env.PROOF_DIR}/bulk-result.png` });
  expect(sent).toHaveLength(1);
  expect([...sent[0]].sort()).toEqual([APPROVAL_CASE.packageId, second.packageId].sort());
});

test('task 0076: no bulk approval while approval before delivery is off', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, []);
  await page.route('**/api/v1/bulk-approval/offer', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ enabled: false, maximum: 50, reviewedByYou: [] }) }));
  await signIn(page);
  await page.getByRole('group', { name: 'Cases by lifecycle stage' }).getByRole('button', { name: /^Approval/ }).click();
  await expect(row(page, 'LN-2002')).toBeVisible();
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Approve selected/ })).toHaveCount(0);
});
