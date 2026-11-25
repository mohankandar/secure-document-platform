import { expect, type Page } from '@playwright/test';
import { APPROVAL_CASE, APPROVAL_READY, DELIVERED_CASE, NOTIFICATIONS_BUSINESS, NOTIFICATIONS_IT, REVIEW_CASE, configuration, signInAs, type MockOptions, type Who } from './harness';
import { dailyReport } from '../e2e-oidc/dailyReportFixture';

/**
 * Task 0089: every screen and state of the tasks since the first accessibility check (0058, 0059, 0061, 0062, 0071, 0073,
 * 0074, 0076, 0078, 0079, 0081, 0082) that screens.spec.ts did not open yet. One list, read by the axe pass (wide and
 * 320 px) and the reflow, 200 % zoom and text spacing passes. Same mocked harness; fake data only.
 */

/** Answers one API path (under /api/v1) instead of the harness; `hang` never answers (the loading state). */
export const answer = (page: Page, path: string, body: unknown, status = 200) =>
  page.route(`**/api/v1${path}`, (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }));
const hang = (page: Page, path: string) => page.route(`**/api/v1${path}`, () => undefined);
const BROKEN = { message: 'Something went wrong', correlationId: 'c0ffee00-0000-4000-8000-0000000000ff', code: 'LV-SYS-006' };
type Answers = (page: Page) => Promise<unknown>;

/** Signs in, sets the answers of this state, then opens the address, so the first load already gets them. */
export async function openAt(page: Page, who: Who, path: string, answers?: Answers, options: MockOptions = {}) {
  await signInAs(page, who, '/', options);
  await answers?.(page);
  await page.goto(path);
  await page.locator('#cf-main h1').first().waitFor();
}

/* ---------- Fixtures ---------- */

const named = (loan: string) => APPROVAL_CASE.businessIdentifiers.map((i) => (i.primary ? { ...i, value: loan } : i));
export const SECOND_APPROVAL = { ...APPROVAL_CASE, packageId: 'c9c9c9c9-0000-4000-8000-000000000012', priority: 'URGENT', businessIdentifiers: named('LN-3012') };
export const OWN_APPROVAL = { ...APPROVAL_CASE, packageId: 'c9c9c9c9-0000-4000-8000-000000000013', businessIdentifiers: named('LN-3013') };
const REJECTED_CASE = { ...DELIVERED_CASE, packageId: 'c9c9c9c9-0000-4000-8000-000000000014', status: 'REJECTED', rejectionCode: 'LV-REV-010', stopReasonCode: 'LV-REV-010' };

const field = { required: false, primary: false, searchable: true, extractable: false, displayable: true, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [] as string[], searchMode: 'EXACT', sensitive: false, multiValue: false };
const FIELDS = [
  { ...field, id: 'f1', key: 'loan_number', label: 'Loan number', required: true, primary: true, displayOrder: 1, validationType: 'NUMBER', customPattern: '[0-9]+', exactLength: 6, locked: true },
  { ...field, id: 'f2', key: 'branch', label: 'Branch', displayOrder: 2, validationType: 'DROPDOWN', allowedValues: ['North', 'South'] },
  { ...field, id: 'f3', key: 'amount', label: 'Amount', displayOrder: 3, validationType: 'MONEY', currency: 'USD' },
  { ...field, id: 'f4', key: 'opened', label: 'Opened', displayOrder: 4, validationType: 'DATE' },
  { ...field, id: 'f5', key: 'property', label: 'Property address', displayOrder: 5, validationType: 'ADDRESS', sensitive: true },
  { ...field, id: 'f6', key: 'borrower_name', label: 'Borrower name', displayOrder: 6, validationType: 'TEXT', sensitive: true, multiValue: true },
];
const fieldsConfiguration = () => ({ ...configuration(), identifiers: FIELDS, sensitiveFieldsAvailable: true,
  switchedOffIdentifiers: [{ ...field, id: 'f9', key: 'old_ref', label: 'Old reference', displayOrder: 9, validationType: 'TEXT' }] });

const KIND = (key: string, locked = false, lastFourAllowed = false) => ({ key, name: key, detectionMethod: 'PATTERN', defaultStyle: 'REMOVE_FULLY', locked, lastFourAllowed });
const CATALOG = {
  kinds: [KIND('SSN', false, true), KIND('DATE_OF_BIRTH'), KIND('CREDIT_CARD_NUMBER', true), KIND('CREDIT_SCORE'), KIND('ITIN', false, true), KIND('EIN', false, true), KIND('PERSON_NAME'), KIND('CVV', true), KIND('PIN', true)],
  packs: [
    { key: 'PCI_DSS', name: 'PCI DSS', covers: 'Payment card data.', notCovered: 'Cardholder names are not found.', kinds: ['CREDIT_CARD_NUMBER', 'CVV', 'PIN'] },
    { key: 'FRCP_5_2', name: 'FRCP 5.2 (court filings)', covers: 'Social Security and taxpayer numbers.', notCovered: 'Names of minors are not found.', kinds: ['SSN', 'ITIN', 'EIN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER'], suggestedKinds: ['PERSON_NAME'] },
  ],
};
function catalogConfiguration(lockedItemsOn: boolean) {
  const c = configuration();
  return { ...c, redactionCatalog: CATALOG, sensitiveDataPolicy: { ...c.sensitiveDataPolicy, enabledPacks: ['FRCP_5_2'], keepLastFourKinds: ['SSN'], lockedItemsOn } };
}

const pageFinding = (findingId: string, pageNumber: number, secondCheck?: string[]) => ({ findingId, packageId: REVIEW_CASE.packageId, documentId: 'doc-1', fileName: 'Closing_Disclosure.pdf', entityType: 'UNREAD_PAGE',
  reviewTaskId: 't1', confidence: 0, pageNumber, hasBoundingBox: false, boundingBox: null, reason: 'LOCATION_UNCERTAIN', foundText: null, foundBy: ['PAGE_CHECK'], secondCheck });
const pagesToCheck = (secondCheck?: string[]) => ({ totalItems: 2, items: [pageFinding('p1', 2, secondCheck), pageFinding('p2', 5, secondCheck)], autoRedactThreshold: 0.8 });

export const BULK = { source: 'c-los', from: '19:00', to: '06:00', weekends: true, open: false, done: 12400, waiting: 27600, failed: 3, perOpenHour: 120, expectedFinish: '2026-12-20T06:00:00Z' };
const emptyDay = () => ({ ...dailyReport(), quiet: [], reasons: [], destinations: [], sources: [], refusalsCounted: false,
  received: { cases: 0, documents: 0, pages: 0, urgent: 0, usual: null }, exported: { cases: 0, documents: 0, pages: 0, splitDocuments: 0, urgent: 0, usual: null },
  notExported: { cases: 0, ready: 0, waitingToRetry: 0, destinationPaused: 0, waitingForYou: 0, urgent: 0, oldestWaitingSeconds: null },
  attention: { refused: 0, stopped: 0, exportFailed: 0, rejected: 0 } });

/* ---------- Opening each screen ---------- */

export const entities = (page: Page, answers?: Answers) => openAt(page, 'business', '/admin/entities', answers);
async function entityDialog(page: Page, button: string, answers?: Answers) {
  await entities(page, answers);
  await page.getByRole('button', { name: button }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
}
async function entityMenu(page: Page) {
  await signInAs(page, 'processor', '/', { twoEntities: true });
  await page.getByRole('button', { name: /switch entity/i }).click();
}
export const approvalLink = (page: Page, body: unknown, status = 200, who: Who = 'supervisor') =>
  openAt(page, who, '/approvals/link/AQEBfake.c2ln', (p) => answer(p, '/approvals/link/open', body, status));
export async function newCase(page: Page) {
  await openAt(page, 'processor', '/intake', (p) => answer(p, '/intake/identifier-definitions', FIELDS));
  await expect(page.getByLabel('Property address: Street', { exact: true })).toBeVisible();
}
export async function adminFields(page: Page) {
  await openAt(page, 'business', '/admin/fields', (p) => answer(p, '/business-admin/configuration', fieldsConfiguration()));
  await expect(page.getByRole('button', { name: 'Switch on Old reference' })).toBeVisible();
}
export async function catalog(page: Page, lockedOn = true) {
  await openAt(page, 'business', '/admin/policy', (p) => answer(p, '/business-admin/configuration', catalogConfiguration(lockedOn)));
  await expect(page.getByRole('switch', { name: /^FRCP 5.2/ })).toBeVisible();
}
async function rejectDialog(page: Page, where: 'panel' | 'review') {
  if (where === 'panel') await openAt(page, 'supervisor', `/case/${APPROVAL_CASE.packageId}`);
  else { await openAt(page, 'processor', '/review'); await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('412-55-0193'); }
  await page.getByRole('button', { name: 'Reject case' }).click();
  await expect(page.getByRole('dialog', { name: /^Reject case/ })).toBeVisible();
}
export async function pageCheck(page: Page, secondCheck?: string[]) {
  await openAt(page, 'processor', '/review', (p) => answer(p, '/review-items?*', pagesToCheck(secondCheck)));
  await expect(page.getByRole('heading', { name: 'Page to check' })).toBeVisible();
  await expect(page.locator('.cf-page img')).toBeVisible();
}
export async function drawMark(page: Page) {
  const drawn = (await page.locator('.cf-page').boundingBox())!;
  await page.mouse.move(drawn.x + 60, drawn.y + 60);
  await page.mouse.down();
  await page.mouse.move(drawn.x + 160, drawn.y + 110, { steps: 4 });
  await page.mouse.up();
  await expect(page.locator('.cf-page-marks')).toContainText('Mark 1');
}
export async function bulkApprove(page: Page) {
  await openAt(page, 'supervisor', '/?stage=approval', async (p) => {
    await answer(p, '/packages', [APPROVAL_CASE, SECOND_APPROVAL, OWN_APPROVAL]);
    await answer(p, '/bulk-approval/offer', { enabled: true, maximum: 50, reviewedByYou: [OWN_APPROVAL.packageId] });
  });
  await expect(page.getByRole('checkbox', { name: 'Select LN-3002' })).toBeVisible();
}
async function bulkConfirm(page: Page) {
  await bulkApprove(page);
  await page.getByRole('checkbox', { name: 'Select LN-3002' }).check();
  await page.getByRole('checkbox', { name: 'Select LN-3012' }).check();
  await page.getByRole('button', { name: 'Approve selected (2)' }).click();
  await expect(page.getByRole('dialog', { name: 'Approve 2 cases for delivery?' })).toBeVisible();
}
export async function sources(page: Page, bulk: object[], answers?: Answers) {
  await openAt(page, 'it', '/admin/integrations', async (p) => {
    await answer(p, '/source-priorities', { sources: { 'c-los': 'LOW' } });
    await answer(p, '/bulk-sources', { sources: bulk });
    await answers?.(p);
  });
  await expect(page.getByLabel('Priority of cases from this source').first()).toBeVisible();
}
export const reportsTab = (page: Page, tab: 'daily' | 'operations' | 'overview', answers?: Answers) =>
  openAt(page, 'business', tab === 'overview' ? '/reports' : `/reports?tab=${tab}`, answers);
const daily = (page: Page, body: unknown, status = 200) => reportsTab(page, 'daily', (p) => answer(p, '/operations/reports/daily?*', body, status));
export const adminQueue = (page: Page, answers?: Answers) => openAt(page, 'ops', '/admin/health', answers);
async function guide(page: Page, query: string) {
  await openAt(page, 'viewer', '/help/error-codes');
  await page.locator('.cf-help').getByRole('textbox').fill(query);
}
const refuse = (path: string): Answers => (p) => p.route(`**/api/v1${path}`, (r) => (r.request().method() === 'GET' ? r.fallback()
  : r.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify(BROKEN) })));

export interface State { task: string; name: string; open: (page: Page) => Promise<unknown>; /** Text that must be on the screen, so a state that did not open can't pass. */ shows: string | RegExp }

export const STATES: State[] = [
  /* 0058: entities and the switcher */
  { task: '0058', name: 'Entities: rename', shows: 'People see this name at the top of every screen', open: (p) => entityDialog(p, 'Rename: Commercial lending') },
  { task: '0058', name: 'Entities: resume', shows: 'documents are taken in again', open: (p) => entityDialog(p, 'Resume: Northwind Trust') },
  { task: '0058', name: 'Entities: a refused change', shows: 'already has this name', open: async (p) => {
    await entityDialog(p, 'Rename: Commercial lending', (x) => answer(x, '/admin/entities/**', { done: false, message: 'Another entity already has this name.', whatToDo: 'Choose another one.' }));
    await p.getByRole('dialog').getByRole('textbox').fill('Residential lending');
    await p.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  } },
  { task: '0058', name: 'Entities: none', shows: 'No entities to show', open: (p) => entities(p, (x) => answer(x, '/admin/entities', { entities: [] })) },
  { task: '0058', name: 'Entities: not loaded', shows: "Entities couldn't be loaded", open: (p) => entities(p, (x) => answer(x, '/admin/entities', BROKEN, 500)) },
  { task: '0058', name: 'Switcher: open', shows: 'Commercial lending', open: entityMenu },
  { task: '0058', name: 'Switcher: unsaved work', shows: 'Switch anyway', open: async (p) => {
    await signInAs(p, 'processor', '/intake', { twoEntities: true });
    await p.locator('.cf-fields input').first().fill('LN-5858');
    await p.getByRole('button', { name: /switch entity/i }).click();
    await p.getByRole('menuitemradio', { name: /Commercial lending/ }).click();
  } },
  { task: '0058', name: 'Switcher: refused', shows: 'Could not switch to Commercial lending', open: async (p) => {
    await entityMenu(p);
    await expect(p.getByRole('menuitemradio', { name: /Commercial lending/ })).toBeVisible();
    await answer(p, '/me', BROKEN, 403);
    await answer(p, '/me/**', BROKEN, 403);
    await p.getByRole('menuitemradio', { name: /Commercial lending/ }).click();
  } },
  { task: '0058', name: 'Switcher: list not loaded', shows: 'Your entities could not be loaded', open: async (p) => {
    await signInAs(p, 'processor', '/', { twoEntities: true });
    await expect(p.getByRole('button', { name: /switch entity/i })).toBeVisible();
    await answer(p, '/me/tenants', BROKEN, 500);
    await p.getByRole('button', { name: /switch entity/i }).click();
  } },

  /* 0059: notifications and the approval page */
  { task: '0059', name: 'Notifications: own server', shows: 'Leave empty for a relay without a login', open: (p) => openAt(p, 'it', '/admin/notifications', undefined, { notificationsIt: { ...NOTIFICATIONS_IT, mail: { ...NOTIFICATIONS_IT.mail, mode: 'OWN_SERVER', host: 'relay.example', port: 587, security: 'STARTTLS', username: 'lexavault' } } }) },
  { task: '0059', name: 'Notifications: not set up, no messages yet', shows: 'None yet', open: (p) => openAt(p, 'it', '/admin/notifications', undefined, { notificationsIt: { ...NOTIFICATIONS_IT, mail: null, appAddressSet: false, approvalLinksOn: false, recent: [] } }) },
  { task: '0059', name: 'Notifications: settings refused', shows: 'Something went wrong', open: async (p) => {
    await openAt(p, 'it', '/admin/notifications', refuse('/admin/notifications/**'));
    await p.getByLabel('Sender name').fill('LexaVault Support');
    await p.getByRole('button', { name: 'Save', exact: true }).click();
  } },
  { task: '0059', name: 'Notifications: change a message, wrong number', shows: 'Tell again every', open: async (p) => {
    await openAt(p, 'it', '/admin/notifications');
    await p.getByRole('button', { name: 'Change: Repeated processing failures' }).click();
    await p.getByRole('dialog').getByLabel(/^Tell after/).fill('9999');
  } },
  { task: '0059', name: 'Notifications: change refused', shows: 'Something went wrong', open: async (p) => {
    await openAt(p, 'business', '/admin/notifications', refuse('/admin/notifications/**'));
    await p.getByRole('button', { name: 'Change: Licence expiring' }).click();
    await p.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  } },
  { task: '0059', name: 'Notifications (business): e-mail not set up', shows: 'E-mail is not set up for this entity yet', open: (p) => openAt(p, 'business', '/admin/notifications', (x) => answer(x, '/admin/notifications/business', { ...NOTIFICATIONS_BUSINESS, mailOn: false })) },
  { task: '0059', name: 'Notifications: not loaded', shows: "Notifications couldn't be loaded", open: (p) => openAt(p, 'it', '/admin/notifications', (x) => answer(x, '/admin/notifications/it', BROKEN, 500)) },
  { task: '0059', name: 'Approval link: decline first', shows: 'Decline delivery', open: (p) => approvalLink(p, { ...APPROVAL_READY, action: 'DECLINE' }) },
  { task: '0059', name: 'Approval link: refused', shows: 'This link was already used', open: (p) => approvalLink(p, { ...APPROVAL_READY, state: 'REFUSED', code: 'LV-REV-002', message: 'This link was already used.' }) },
  { task: '0059', name: 'Approval link: decided', shows: 'The case was approved', open: (p) => approvalLink(p, { ...APPROVAL_READY, state: 'APPROVED', message: 'The case was approved.' }) },
  { task: '0059', name: 'Approval link: not checked', shows: 'Try again', open: (p) => approvalLink(p, BROKEN, 500) },
  { task: '0059', name: 'Approval link: decision not sent', shows: 'Something went wrong', open: async (p) => {
    await approvalLink(p, APPROVAL_READY);
    await refuse('/approvals/link/**')(p);
    await p.getByRole('button', { name: 'Approve delivery' }).click();
  } },
  { task: '0059', name: 'Approval link: checking', shows: 'Checking the link', open: (p) => openAt(p, 'supervisor', '/approvals/link/AQEBfake.c2ln', (x) => hang(x, '/approvals/link/open')) },
  { task: '0059', name: 'Approval link: not a Supervisor', shows: 'Only a Supervisor of this entity', open: (p) => approvalLink(p, APPROVAL_READY, 200, 'processor') },

  /* 0061: case fields */
  { task: '0061', name: 'New case: every kind of field', shows: 'Borrower name', open: newCase },
  { task: '0061', name: 'New case: values that break a rule, several values', shows: 'Enter an amount', open: async (p) => {
    await newCase(p);
    await p.getByRole('button', { name: 'Add another' }).click();
    await p.getByLabel('Loan number').fill('12');
    await p.getByLabel('Amount').fill('much');
    await p.getByLabel('Amount').blur();
  } },
  { task: '0061', name: 'New case: fields not loaded', shows: "Identity fields couldn't be loaded", open: (p) => openAt(p, 'processor', '/intake', (x) => answer(x, '/intake/identifier-definitions', BROKEN, 500)) },
  { task: '0061', name: 'Admin fields: every kind, one switched off', shows: 'Old reference', open: adminFields },
  { task: '0061', name: 'Admin fields: a field with values', shows: 'This field has values', open: async (p) => { await adminFields(p); await p.getByRole('button', { name: 'Edit Loan number' }).click(); } },
  { task: '0061', name: 'Admin fields: a list field', shows: 'One per line or separated by commas', open: async (p) => { await adminFields(p); await p.getByRole('button', { name: 'Edit Branch' }).click(); } },
  { task: '0061', name: 'Admin fields: a field without a name', shows: 'Every case field needs a name', open: async (p) => { await adminFields(p); await p.getByRole('button', { name: 'Add case field' }).click(); await p.getByRole('button', { name: 'Add to list' }).click(); } },

  /* 0062: redaction catalog and packs */
  { task: '0062', name: 'Catalog: packs, locked items, styles', shows: 'FRCP 5.2 (court filings)', open: (p) => catalog(p) },
  { task: '0062', name: 'Catalog: locked items from the next save', shows: 'They turn on with your next save', open: (p) => catalog(p, false) },
  { task: '0062', name: 'Catalog: save refused', shows: 'Something went wrong', open: async (p) => {
    await catalog(p);
    await refuse('/business-admin/sensitive-data-policy')(p);
    await p.locator('.cf-ad-toggle', { hasText: 'PCI DSS' }).click();
    await p.getByRole('region', { name: 'Sensitive data' }).getByRole('button', { name: /^Save/ }).click();
  } },

  /* 0071: reject */
  { task: '0071', name: 'Reject: from the case panel, Other', shows: 'Reject case LN-3002?', open: async (p) => { await rejectDialog(p, 'panel'); await p.getByRole('dialog').getByRole('combobox').selectOption('LV-REV-014'); } },
  { task: '0071', name: 'Reject: from Review', shows: 'Reject case LN-3001?', open: (p) => rejectDialog(p, 'review') },
  { task: '0071', name: 'Reject: refused', shows: 'Something went wrong', open: async (p) => {
    await rejectDialog(p, 'panel');
    await answer(p, '/packages/*/approval/reject', BROKEN, 500);
    await p.getByRole('dialog').getByRole('combobox').selectOption({ index: 1 });
    await p.getByRole('dialog').getByRole('button', { name: /^Reject/ }).click();
  } },
  { task: '0071', name: 'Reject: a rejected case', shows: /Rejected: /, open: (p) => openAt(p, 'supervisor', `/case/${REJECTED_CASE.packageId}`, async (x) => { await answer(x, '/packages', [REJECTED_CASE]); await answer(x, `/packages/${REJECTED_CASE.packageId}`, REJECTED_CASE); }) },

  /* 0073: priority */
  { task: '0073', name: 'Priority: Urgent on Cases and in the case panel', shows: 'Urgent', open: async (p) => {
    await openAt(p, 'supervisor', `/case/${SECOND_APPROVAL.packageId}`, async (x) => { await answer(x, '/packages', [APPROVAL_CASE, SECOND_APPROVAL]); await answer(x, `/packages/${SECOND_APPROVAL.packageId}`, SECOND_APPROVAL); });
    await expect(p.getByTestId('case-priority')).toBeVisible();
  } },
  { task: '0073', name: 'Priority: change refused (message)', shows: 'Something went wrong', open: async (p) => {
    await openAt(p, 'supervisor', `/case/${APPROVAL_CASE.packageId}`, (x) => answer(x, '/packages/*/priority', BROKEN, 500));
    await p.getByTestId('case-priority').getByRole('combobox').selectOption('LOW');
  } },

  /* 0074: page to check */
  { task: '0074', name: 'Page to check', shows: 'Page to check', open: (p) => pageCheck(p) },
  { task: '0074', name: 'Page to check: second check', shows: /second check|check again|checked twice/i, open: (p) => pageCheck(p, ['SSN']) },
  { task: '0074', name: 'Page to check: marked and confirmed', shows: 'Mark 1', open: async (p) => { await pageCheck(p); await drawMark(p); await p.locator('.cf-page-reviewed input').check(); } },
  { task: '0074', name: 'Page to check: refused', shows: 'Something went wrong', open: async (p) => {
    await pageCheck(p);
    await answer(p, '/review-items/*/page-checked', BROKEN, 500);
    await p.locator('.cf-page-reviewed input').check();
    await p.locator('.cf-decide').first().click();
  } },

  /* 0076: bulk approval */
  { task: '0076', name: 'Bulk approve: one of the person’s own cases', shows: 'You reviewed this case', open: bulkApprove },
  { task: '0076', name: 'Bulk approve: confirm', shows: 'Approve 2 cases for delivery?', open: bulkConfirm },
  { task: '0076', name: 'Bulk approve: refused', shows: 'Something went wrong', open: async (p) => { await bulkConfirm(p); await answer(p, '/bulk-approval', BROKEN, 500); await p.getByRole('button', { name: 'Approve 2' }).click(); } },
  { task: '0076', name: 'Bulk approve: result with one that stayed', shows: '1 case approved.', open: async (p) => {
    await bulkConfirm(p);
    await answer(p, '/bulk-approval', [{ packageId: APPROVAL_CASE.packageId, approved: true, reasonCode: null }, { packageId: SECOND_APPROVAL.packageId, approved: false, reasonCode: 'LV-REV-002' }]);
    await p.getByRole('button', { name: 'Approve 2' }).click();
  } },

  /* 0078: queue and time left */
  { task: '0078', name: 'Queue (admin): how it is worked out', shows: 'Queue, every entity together', open: async (p) => { await adminQueue(p); await p.getByRole('button', { name: 'How is this worked out?' }).click(); await expect(p.locator('#cf-pq-how')).toBeVisible(); } },
  { task: '0078', name: 'Queue (admin): not in steps', shows: 'Queue, every entity together', open: (p) => adminQueue(p, (x) => answer(x, '/platform-admin/queue', { staged: false, allEntities: true, windowMinutes: 10, etaSeconds: null, stages: [] })) },
  { task: '0078', name: 'Queue (admin): not loaded', shows: "Queue couldn't be loaded", open: (p) => adminQueue(p, (x) => answer(x, '/platform-admin/queue', BROKEN, 500)) },
  { task: '0078', name: 'Queue (Reports): how it is worked out', shows: 'The whole queue as it is now', open: async (p) => { await reportsTab(p, 'operations'); await p.locator('[aria-labelledby="cf-pq-head"]').getByRole('button', { name: 'How is this worked out?' }).click(); await expect(p.locator('#cf-pq-how')).toBeVisible(); } },

  /* 0079: Bulk sources */
  { task: '0079', name: 'Bulk source: on, times changed', shows: '12,400 of', open: async (p) => { await sources(p, [BULK]); await p.getByLabel('Weeknights from').fill('21:30'); await expect(p.getByRole('button', { name: 'Save times' })).toBeVisible(); } },
  { task: '0079', name: 'Bulk source: end', shows: 'End migration', open: async (p) => { await sources(p, [BULK]); await p.getByTestId('source-bulk').first().locator('.cf-ad-toggle').first().click(); await expect(p.getByRole('dialog')).toBeVisible(); } },
  { task: '0079', name: 'Bulk source: refused (message)', shows: 'Something went wrong', open: async (p) => {
    await sources(p, [], refuse('/bulk-sources/*'));
    await p.getByTestId('source-bulk').first().locator('.cf-ad-toggle').first().click();
  } },

  /* 0081: Reports tabs */
  { task: '0081', name: 'Reports: Overview', shows: 'What LexaVault did for you', open: (p) => reportsTab(p, 'overview') },
  { task: '0081', name: 'Reports: Daily, own range', shows: 'By sensitivity level', open: async (p) => { await reportsTab(p, 'daily'); await p.getByRole('radio', { name: 'Custom' }).click(); await expect(p.locator('.cf-rp-range')).toBeVisible(); } },
  { task: '0081', name: 'Reports: Daily, wrong range', shows: 'The end date must be on or after the start date', open: async (p) => { await reportsTab(p, 'daily'); await p.getByRole('radio', { name: 'Custom' }).click(); await p.locator('.cf-rp-range input').first().fill('2026-11-20'); await p.locator('.cf-rp-range input').last().fill('2026-11-10'); } },
  { task: '0081', name: 'Reports: Daily, nothing to show', shows: 'Nothing needs attention.', open: (p) => daily(p, emptyDay()) },
  { task: '0081', name: 'Reports: Daily, loading', shows: 'How the work is going', open: (p) => reportsTab(p, 'daily', (x) => hang(x, '/operations/reports/daily?*')) },
  { task: '0081', name: 'Reports: Daily, not loaded', shows: "Reports couldn't be loaded", open: (p) => daily(p, BROKEN, 500) },

  /* 0082: the error code guide */
  { task: '0082', name: 'Guide: one found, open', shows: 'Support tells the customer', open: (p) => guide(p, 'LV-SYS-006') },
  { task: '0082', name: 'Guide: nothing found', shows: /^(no |0 )/i, open: (p) => guide(p, 'zzzzzz') },
  { task: '0082', name: 'Guide without a session: all', shows: 'Error codes', open: (p) => p.goto('/help/error-codes') },
  { task: '0082', name: 'Guide without a session: unknown code', shows: /This code isn.t in the guide/, open: (p) => p.goto('/help/error-codes/LV-XYZ-999') },
];

/** Opens a state and makes sure it is the one on the screen. */
export async function openState(page: Page, s: State) {
  await s.open(page);
  const text = typeof s.shows === 'string' ? new RegExp(s.shows.replace(/[.*+?^${}()[\]\\]/g, '\\$&')) : s.shows;
  await expect(page.locator('.cf').getByText(text).filter({ visible: true }).first()).toBeVisible();
  await page.waitForTimeout(250);
}
