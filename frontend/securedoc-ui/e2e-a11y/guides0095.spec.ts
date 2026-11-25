import { test, expect, type Page } from '@playwright/test';
import { APPROVAL_CASE, APPROVAL_READY, DELIVERED_CASE, INCIDENT, REDACTING_CASE, REVIEW_CASE, STOPPED_CASE, configuration, signInAs } from './harness';
import { BULK, answer, openAt as openWith, sources as openSources } from './states0089';
import { dailyReport } from '../e2e-oidc/dailyReportFixture';

/**
 * Task 0095: the pictures of the guides (docs/training/assets/). Mocked harness, made-up data only. Nothing that a redaction
 * would remove is shown: no found text, no names of people (a case shows its number and branch), blank pages.
 * Registered only on request: GUIDES0095=1 npx playwright test --config playwright.a11y.config.ts guides0095 --project light
 */
if (process.env.GUIDES0095) {
  const OUT = '../../docs/training/assets';
  test.use({ viewport: { width: 1280, height: 800 } });

  /** The product's own name in the top bar (the harness answers no branding). */
  const branded = (page: Page) => answer(page, '/branding/current', { applicationName: 'LexaVault' });
  const openAt: typeof openWith = (page, who, path, answers, options) => openWith(page, who, path, async (p) => { await branded(p); await answers?.(p); }, options);
  const sources: typeof openSources = (page, bulk) => openSources(page, bulk, branded);

  type Case = typeof APPROVAL_CASE;
  const made = (c: Case, loan: string, more: object = {}) => ({ ...c, documents: [{ documentId: 'doc-1', fileName: 'Closing_Disclosure.pdf', sizeBytes: 482_000, status: 'REDACTED' }], ...more, businessIdentifiers: [
    { ...c.businessIdentifiers[0], value: loan },
    { key: 'branch', label: 'Branch', value: 'North', primary: false, searchable: true, displayable: true, displayOrder: 2 },
  ] });
  const id = (n: string) => `c9c9c9c9-0000-4000-8000-0000000000${n}`;
  const REVIEW = made(REVIEW_CASE, 'LN-3001');
  const APPROVAL = made(APPROVAL_CASE, 'LN-3002');
  const URGENT = made(APPROVAL_CASE, 'LN-3012', { packageId: id('12'), priority: 'URGENT' });
  const OWN = made(APPROVAL_CASE, 'LN-3013', { packageId: id('13') });
  const REJECTED = made(DELIVERED_CASE, 'LN-3014', { packageId: id('14'), status: 'REJECTED', rejectionCode: 'LV-REV-010', stopReasonCode: 'LV-REV-010' });
  const STOPPED = made(STOPPED_CASE, 'LN-3003');
  const ALL = [REVIEW, APPROVAL, URGENT, OWN, STOPPED, made(REDACTING_CASE, 'LN-3005'), made(DELIVERED_CASE, 'LN-3004'), REJECTED];
  async function cases(page: Page, list = ALL) {
    await answer(page, '/packages', list);
    for (const c of list) await answer(page, `/packages/${c.packageId}`, c);
  }
  const shot = async (page: Page, name: string) => { await page.waitForTimeout(400); await page.screenshot({ path: `${OUT}/${name}.png` }); };

  test('cases', async ({ page }) => {
    await openAt(page, 'supervisor', '/', (p) => cases(p));
    await expect(page.getByRole('button', { name: 'Approve LN-3012' })).toBeVisible();
    await shot(page, 'cases');
  });

  test('case panel', async ({ page }) => {
    await openAt(page, 'supervisor', `/case/${URGENT.packageId}`, (p) => cases(p));
    await expect(page.getByRole('button', { name: 'Reject case' })).toBeVisible();
    await shot(page, 'case-panel');
    await page.getByRole('button', { name: 'Reject case' }).click();
    await page.getByRole('dialog').getByRole('combobox').selectOption('LV-REV-010');
    await shot(page, 'reject-case');
  });

  test('new case', async ({ page }) => {
    const f = { required: false, primary: false, searchable: true, extractable: false, displayable: true, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [] as string[], searchMode: 'EXACT', sensitive: false, multiValue: false };
    await openAt(page, 'processor', '/intake', (p) => answer(p, '/intake/identifier-definitions', [
      { ...f, id: 'f1', key: 'loan_number', label: 'Loan number', required: true, primary: true, displayOrder: 1, validationType: 'NUMBER', customPattern: '[0-9]+', exactLength: 6 },
      { ...f, id: 'f2', key: 'branch', label: 'Branch', displayOrder: 2, validationType: 'DROPDOWN', allowedValues: ['North', 'South'] },
      { ...f, id: 'f3', key: 'borrower_name', label: 'Borrower name', displayOrder: 3, validationType: 'TEXT', sensitive: true, multiValue: true },
    ]));
    await page.getByLabel('Loan number').fill('300100');
    await page.locator('#cf-id-priority').selectOption('URGENT');
    await shot(page, 'new-case');
  });

  const item = (findingId: string, entityType: string, pageNumber: number, more: object = {}) => ({ findingId, packageId: REVIEW.packageId, documentId: 'doc-1', fileName: 'Closing_Disclosure.pdf',
    entityType, reviewTaskId: 't1', confidence: 0.62, pageNumber, hasBoundingBox: true, boundingBox: { left: 0.2, top: 0.2, width: 0.2, height: 0.03 }, reason: 'LOW_CONFIDENCE', foundText: null, foundBy: ['TEXT'], ...more });
  const toCheck = (findingId: string, pageNumber: number, secondCheck?: string[]) => item(findingId, 'UNREAD_PAGE', pageNumber, { confidence: 0, hasBoundingBox: false, boundingBox: null, reason: 'LOCATION_UNCERTAIN', foundBy: ['PAGE_CHECK'], secondCheck });
  async function review(page: Page, items: object[]) {
    await openAt(page, 'processor', '/review', async (p) => { await cases(p); await answer(p, '/review-items?*', { totalItems: items.length, items, autoRedactThreshold: 0.8 }); });
    await expect(page.locator('.cf-page img')).toBeVisible();
  }

  test('review', async ({ page }) => {
    await review(page, [item('f1', 'SSN', 1), item('f2', 'SSN', 2), item('f3', 'DATE_OF_BIRTH', 2)]);
    await expect(page.getByRole('button', { name: /^Redact all 2 like this/ })).toBeVisible();
    await shot(page, 'review');
  });

  test('page to check', async ({ page }) => {
    await review(page, [toCheck('p1', 2), toCheck('p2', 5)]);
    const drawn = (await page.locator('.cf-page').boundingBox())!;
    await page.mouse.move(drawn.x + 90, drawn.y + 90);
    await page.mouse.down();
    await page.mouse.move(drawn.x + 260, drawn.y + 130, { steps: 4 });
    await page.mouse.up();
    await page.locator('.cf-page-reviewed input').check();
    await shot(page, 'page-to-check');
  });

  test('second check', async ({ page }) => {
    await review(page, [toCheck('p1', 3, ['SSN'])]);
    await expect(page.getByText('Second check found something on page 3: SSN', { exact: true })).toBeVisible();
    await shot(page, 'second-check');
  });

  test('bulk approve', async ({ page }) => {
    await openAt(page, 'supervisor', '/?stage=approval', async (p) => {
      await cases(p, [APPROVAL, URGENT, OWN]);
      await answer(p, '/bulk-approval/offer', { enabled: true, maximum: 50, reviewedByYou: [OWN.packageId] });
    });
    await page.getByRole('checkbox', { name: 'Select LN-3002' }).check();
    await page.getByRole('checkbox', { name: 'Select LN-3012' }).check();
    await shot(page, 'bulk-approve');
  });

  test('continue manually', async ({ page }) => {
    await openAt(page, 'processor', `/packages/${STOPPED.packageId}/manual-processing`, (p) => cases(p));
    await expect(page.getByRole('heading', { name: 'Mark a value' })).toBeVisible();
    await expect(page.locator('.cf-page img')).toBeVisible();
    await shot(page, 'manual-processing-business-continuity');
  });

  test('approval link', async ({ page }) => {
    await openAt(page, 'supervisor', '/approvals/link/AQEBfake.c2ln', (p) => answer(p, '/approvals/link/open', { ...APPROVAL_READY, askedBy: 'A Document Processor', expiresAt: new Date(Date.now() + 20 * 3_600_000).toISOString() }));
    await expect(page.getByRole('heading', { name: 'Deliver case LN-3002?' })).toBeVisible();
    await shot(page, 'approval-link');
  });

  test('entity switch', async ({ page }) => {
    await signInAs(page, 'supervisor', '/', { twoEntities: true });
    await branded(page);
    await cases(page);
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Approve LN-3012' })).toBeVisible();
    await page.getByRole('button', { name: /switch entity/i }).click();
    await expect(page.getByRole('menu', { name: 'Entities' })).toBeVisible();
    await shot(page, 'entity-switch');
  });

  test('catalog', async ({ page }) => {
    const kind = (key: string, locked = false, lastFourAllowed = false) => ({ key, name: key, detectionMethod: 'PATTERN', defaultStyle: 'REMOVE_FULLY', locked, lastFourAllowed });
    const kinds = [kind('SSN', false, true), kind('DATE_OF_BIRTH'), kind('CREDIT_CARD_NUMBER', true), kind('CREDIT_SCORE'), kind('BANK_ROUTING_NUMBER', false, true), kind('BANK_ACCOUNT_NUMBER', false, true), kind('ITIN', false, true),
      kind('EIN', false, true), kind('DRIVERS_LICENSE_NUMBER', false, true), kind('PASSPORT_NUMBER', false, true), kind('PERSON_NAME'), kind('SIGNATURE'), kind('FACE_PHOTO'), kind('CVV', true), kind('PIN', true)];
    // The words of RegulationPack.java.
    const packs = [
      { key: 'GLBA', name: 'GLBA / financial', covers: 'Customer financial information: Social Security Numbers, tax IDs (ITIN, EIN), dates of birth, bank account and routing numbers, card numbers, card security codes, PINs, credit scores, driver’s license and passport numbers.',
        notCovered: 'Names, addresses, income, balances and loan terms are not found.',
        kinds: ['SSN', 'ITIN', 'EIN', 'DATE_OF_BIRTH', 'BANK_ACCOUNT_NUMBER', 'BANK_ROUTING_NUMBER', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE', 'DRIVERS_LICENSE_NUMBER', 'PASSPORT_NUMBER', 'CVV', 'PIN'] },
      { key: 'PCI_DSS', name: 'PCI DSS', covers: 'Payment card data: full card numbers, card security codes (CVV) and PINs. All three are locked items, always removed in full.',
        notCovered: 'Cardholder names, expiration dates and magnetic stripe data are not found.', kinds: ['CREDIT_CARD_NUMBER', 'CVV', 'PIN'] },
      { key: 'HIPAA', name: 'HIPAA', covers: 'The HIPAA identifiers that LexaVault finds: Social Security Numbers, ITINs, dates of birth, bank account and card numbers, driver’s license and passport numbers.',
        notCovered: 'Names, addresses, phone numbers, emails, medical record and health plan numbers, other dates, photos and the remaining HIPAA identifiers are not found.',
        kinds: ['SSN', 'ITIN', 'DATE_OF_BIRTH', 'BANK_ACCOUNT_NUMBER', 'CREDIT_CARD_NUMBER', 'DRIVERS_LICENSE_NUMBER', 'PASSPORT_NUMBER'] },
      { key: 'FRCP_5_2', name: 'FRCP 5.2 (court filings)', covers: 'Federal court filings: Social Security and taxpayer numbers (SSN, ITIN, EIN), dates of birth, bank account and card numbers. The rule allows the last four digits of these numbers; here never of a card number, which is a locked item.',
        notCovered: 'Names of minors are not found unless you also turn on Person name, which finds the names of all persons for Review. A date of birth is covered in full (the rule allows the year).',
        kinds: ['SSN', 'ITIN', 'EIN', 'DATE_OF_BIRTH', 'BANK_ACCOUNT_NUMBER', 'CREDIT_CARD_NUMBER'], suggestedKinds: ['PERSON_NAME'] },
    ];
    const c = configuration();
    await openAt(page, 'business', '/admin/policy', (p) => answer(p, '/business-admin/configuration', { ...c, redactionCatalog: { kinds, packs },
      sensitiveDataPolicy: { ...c.sensitiveDataPolicy, enabledEntityTypes: [...c.sensitiveDataPolicy.enabledEntityTypes, 'ITIN', 'EIN', 'BANK_ACCOUNT_NUMBER', 'CVV', 'PIN'], enabledPacks: ['FRCP_5_2'], keepLastFourKinds: ['SSN'], lockedItemsOn: true, secondCheckEnabled: true } }));
    const packsHeading = page.getByText('Regulation packs', { exact: true });
    await expect(packsHeading).toBeVisible();
    await packsHeading.evaluate((e) => e.scrollIntoView({ block: 'start' }));
    await page.evaluate(() => window.scrollBy(0, -90));
    await shot(page, 'redaction-catalog');
  });

  test('entities', async ({ page }) => {
    await openAt(page, 'business', '/admin/entities');
    await expect(page.getByRole('table', { name: 'Entities you administer' })).toBeVisible();
    await shot(page, 'entities');
  });

  test('case fields', async ({ page }) => {
    const c = configuration();
    await openAt(page, 'business', '/admin/fields', (p) => answer(p, '/business-admin/configuration', { ...c, sensitiveFieldsAvailable: true, switchedOffIdentifiers: [],
      identifiers: c.identifiers.map((i) => ({ ...i, sensitive: i.key === 'borrower_name', multiValue: false, searchMode: 'EXACT' })) }));
    await page.getByRole('button', { name: 'Edit Borrower name' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await shot(page, 'case-fields');
  });

  test('notifications', async ({ page }) => {
    await openAt(page, 'it', '/admin/notifications');
    await expect(page.getByRole('region', { name: 'Last messages' })).toBeVisible();
    await shot(page, 'notifications-it');
  });

  test('bulk source', async ({ page }) => {
    await sources(page, [BULK]);
    await page.getByTestId('source-bulk').first().scrollIntoViewIfNeeded();
    await shot(page, 'bulk-source');
  });

  test('queue', async ({ page }) => {
    await openAt(page, 'it', '/admin/health', (p) => answer(p, '/platform-admin/queue', { staged: true, allEntities: false, windowMinutes: 10, etaSeconds: 2400, stages: [
      { stage: 'TEXT', waiting: 120, running: 4, held: 0, toCome: 0, workers: 4, highest: 6, oldestWaitingSeconds: 310, perMinute: 40, etaSeconds: 190 },
      { stage: 'FIND', waiting: 84, running: 5, held: 0, toCome: 124, workers: 5, highest: 5, oldestWaitingSeconds: 540, perMinute: 5.3, etaSeconds: 2400 },
      { stage: 'VISION', waiting: 0, running: 0, held: 0, toCome: 0, workers: 1, highest: 2, oldestWaitingSeconds: null, perMinute: null, etaSeconds: 0 },
      { stage: 'REDACT', waiting: 2, running: 1, held: 0, toCome: 210, workers: 1, highest: 6, oldestWaitingSeconds: 4, perMinute: 30, etaSeconds: 2400 },
      { stage: 'EXPORT', waiting: 0, running: 0, held: 3, toCome: 22, workers: 1, highest: 8, oldestWaitingSeconds: null, perMinute: 4, etaSeconds: 2400 }] }));
    const card = page.locator('[aria-labelledby="cf-pq-head"]');
    await expect(card).toBeVisible();
    await page.waitForTimeout(400);
    await card.screenshot({ path: `${OUT}/queue.png` });
  });

  test('reports', async ({ page }) => {
    const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
    const made = dailyReport();
    const today = { ...made, generatedAt: ago(0), quiet: [{ ...made.quiet[0], lastReceivedAt: ago(150) }], sources: made.sources.map((x, i) => ({ ...x, lastReceivedAt: ago([150, 2, 900][i]) })) };
    await openAt(page, 'business', '/reports?tab=daily', (p) => answer(p, '/operations/reports/daily?*', today));
    await expect(page.getByRole('region', { name: 'Needs attention, by reason' })).toBeVisible();
    await shot(page, 'reports-daily');
  });

  test('support', async ({ page }) => {
    await openAt(page, 'it', '/support/status');
    await expect(page.getByRole('heading', { name: 'Last failures' })).toBeVisible();
    await shot(page, 'support-status');
  });

  test('problem', async ({ page }) => {
    // An AI problem: the one kind that can also be finished with Continue manually.
    const at = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
    const step = (eventType: string, minutes: number, more: object = {}) => ({ eventType, correlationId: 'corr-1', occurredAt: at(minutes), safeMetadata: {}, ...more });
    await openAt(page, 'it', `/support/incidents/${INCIDENT}`, (p) => answer(p, `/support/incidents/${INCIDENT}`, {
      incidentId: INCIDENT, tenantId: STOPPED.tenantId, packageId: STOPPED.packageId, documentId: 'doc-1', packageStatus: 'FAILED', primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: 'LN-3003',
      stage: 'AI_ANALYSIS', component: 'AI', failureCategory: 'DEPENDENCY_TIMEOUT', reasonCode: 'AI_TIMEOUT', status: 'OPEN', businessImpact: 'BLOCKED', businessOutcome: null, businessContinuityUsed: false,
      retryable: true, attemptCount: 2, correlationId: 'corr-1', safeSummary: 'The AI did not answer in time.', artifactAvailability: 'RETAINED_UNTIL_POLICY_EXPIRY', technicalAttentionRetainedUntil: at(-2 * 1440),
      artifactsPurgedAt: null, processingFingerprint: {}, safeMetadata: {}, firstObservedAt: at(50), lastObservedAt: at(40), resolvedAt: null,
      events: [step('FAILURE_RECORDED', 50, { attemptNumber: 1, reasonCode: 'AI_TIMEOUT', retryable: true }), step('FAILURE_RECORDED', 40, { attemptNumber: 2, reasonCode: 'AI_TIMEOUT', retryable: true }), step('PROCESSING_RECOVERY_EXHAUSTED', 40)],
    }));
    await expect(page.getByRole('heading', { name: 'What you can do' })).toBeVisible();
    await shot(page, 'technical-issue-details-structured-incident');
  });

  test('error codes', async ({ page }) => {
    await openAt(page, 'viewer', '/help/error-codes/LV-SYS-014');
    await expect(page.locator('[data-code="LV-SYS-014"]').getByText('Support tells the customer')).toBeVisible();
    await shot(page, 'error-code-guide');
  });
}
