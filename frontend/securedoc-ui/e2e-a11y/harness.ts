import { expect, type Page, type Route } from '@playwright/test';
import { crc32, deflateSync } from 'node:zlib';
import { capabilitiesFor } from '../e2e-oidc/policyCapabilities';
import { INLINE_EXPORT, exportApi, exportCases, retentionSettings } from '../e2e-oidc/exportFixtures';

/**
 * UI task U026: one mocked identity provider and one mocked API that answers every Case File screen, so the
 * accessibility suite can open each screen as the role that uses it. Fixtures are taken from the e2e-oidc suites.
 */

export const ISSUER = 'http://idp.test/realms/signin-race';
export const ENTITY = { tenantId: 'c9c9c9c9-0000-4000-8000-00000000000c', name: 'Residential lending', slug: 'residential-lending' };
export const OTHER_ENTITY = { tenantId: 'c9c9c9c9-0000-4000-8000-00000000000d', name: 'Commercial lending', slug: 'commercial-lending' };

export const ROLES = {
  processor: ['DOCUMENT_PROCESSOR'],
  supervisor: ['DOCUMENT_SUPERVISOR'],
  business: ['BUSINESS_ADMIN'],
  viewer: ['REPORT_VIEWER'],
  it: ['TENANT_IT_ADMIN'],
  ops: ['PLATFORM_OPERATIONS_ADMIN'],
  none: [] as string[],
} as const;
export type Who = keyof typeof ROLES;

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'a11y-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
const minsAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

export async function mockIdentityProvider(page: Page) {
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
      back.searchParams.set('code', 'a11y-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<script>location.replace(${JSON.stringify(back.toString())})</script>` });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'a11y-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

/* ---------- Cases ---------- */

const id = (n: string) => `c9c9c9c9-0000-4000-8000-00000000${n}`;
function caseFor(loan: string, status: string, n: string, name: string, documents: object[] = []) {
  return {
    packageId: id(n), tenantId: ENTITY.tenantId, packageType: 'MANUAL_UPLOAD', status,
    documentCount: Math.max(1, documents.length), createdAt: minsAgo(300), updatedAt: minsAgo(30), documents,
    businessIdentifiers: [
      { key: 'loan_number', label: 'Loan number', value: loan, primary: true, searchable: true, displayable: true, displayOrder: 1 },
      { key: 'borrower_name', label: 'Borrower name', value: name, primary: false, searchable: true, displayable: true, displayOrder: 2 },
    ],
  };
}
const DOC = 'c9c9c9c9-0000-4000-8000-0000000000d1';
export const REVIEW_CASE = caseFor('LN-3001', 'PENDING_REVIEW', '0001', 'Maria Alvarez');
export const APPROVAL_CASE = caseFor('LN-3002', 'PENDING_FINAL_APPROVAL', '0002', 'James Okafor', [{ documentId: 'doc-2', fileName: 'Closing_Disclosure.pdf' }]);
export const STOPPED_CASE = caseFor('LN-3003', 'FAILED', '0003', 'Priya Natarajan', [{ documentId: DOC, fileName: 'Title_Commitment.pdf' }]);
export const DELIVERED_CASE = caseFor('LN-3004', 'COMPLETED', '0004', 'Daniel Kim');
export const REDACTING_CASE = caseFor('LN-3005', 'PROCESSING', '0005', 'Ana Souza');
const CASES = [REVIEW_CASE, APPROVAL_CASE, STOPPED_CASE, DELIVERED_CASE, REDACTING_CASE];

/* ---------- Task 0029: export screens (MockOptions.exports) ---------- */

export const EXPORT_CASES = exportCases('c9c9c9c9').map((c) => ({
  ...caseFor(c.loan, c.status, c.packageId.slice(-4), 'Borrower', (c.documents ?? []).map((d, i) => ({ documentId: d, fileName: `Document_${i + 1}.pdf` }))),
  packageId: c.packageId, exportState: c.exportState,
}));
const EXPORTS = exportApi(exportCases('c9c9c9c9'));

/* ---------- Review ---------- */

const finding = (fid: string, type: string, text: string, hasBox = true) => ({
  findingId: fid, packageId: REVIEW_CASE.packageId, documentId: 'doc-1', fileName: 'Closing_Disclosure.pdf', entityType: type, reviewTaskId: 't1',
  confidence: 0.62, pageNumber: 1, hasBoundingBox: hasBox, boundingBox: hasBox ? { left: 0.2, top: 0.3, width: 0.2, height: 0.03 } : null,
  reason: hasBox ? 'LOW_CONFIDENCE' : 'LOCATION_UNCERTAIN', foundText: { text, before: 'Borrower SSN: ', after: '' },
});
const FINDINGS = [finding('f1', 'SSN', '412-55-0193'), finding('f2', 'SSN', '518-22-7741'), finding('f3', 'SSN', '623-11-0042', false), finding('f4', 'DATE_OF_BIRTH', '03/14/1979')];

/** A blank page the size the backend renders a US Letter page at (144 DPI): 1224 × 1584, 8-bit grey. */
function blankPagePng(width = 1224, height = 1584): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([length, body, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = 0;
  const rows = Buffer.alloc((width + 1) * height, 0xff);
  for (let y = 0; y < height; y += 1) rows[y * (width + 1)] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const PAGE_PNG = blankPagePng();

/* ---------- Reports ---------- */

const TREND = Array.from({ length: 30 }, (_, i) => ({ bucket: new Date(Date.UTC(2026, 9, 20 + i)).toISOString(), received: 10 + (i % 7), delivered: 8 + (i % 5) }));
function reports() {
  const zeroes = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, 0]));
  return {
    generatedAt: '2026-11-18T20:00:00Z',
    period: { from: '2026-10-19T00:00:00Z', to: '2026-11-18T00:00:00Z', previousFrom: '2026-09-19T00:00:00Z', previousTo: '2026-10-19T00:00:00Z', source: 'ALL', outcome: 'ALL' },
    executive: {
      ...zeroes(['completed', 'previousCompleted', 'periodIntakeDelivered', 'previousPeriodIntakeDelivered', 'completionRate', 'periodIntakeDeliveryRate']),
      batches: 512, previousBatches: 480, documents: 1873, pages: 14220, delivered: 463, previousDelivered: 440, deliveryRate: 0.9, medianDeliveryHours: 2.4, p95DeliveryHours: 9.8,
      straightThroughRate: 0.68, reviewRate: 0.29, currentBacklog: 23, currentAttention: 4, oldestBacklogHours: 30.5, trend: TREND,
    },
    intake: { totalBatches: 512, manual: 210, api: 180, watchedFolder: 90, scanner: 32, waitingForTrigger: 1, duplicateObservations: 2, rejectedObservations: 1 },
    processing: { processing: 3, needsReview: 12, needsApproval: 4, completed: 463, technicalAttention: 4, rejected: 2 },
    export: { exportedPackages: 463, successfulExportCycles: 470, pendingFinalApproval: 4 },
    retention: { originalsDueWithin24Hours: 12, redactedCopiesDueWithin24Hours: 30, technicalHoldsDueWithin24Hours: 1, originalsOverdue: 0, redactedCopiesOverdue: 0, purgedPackages: 300 },
    timing: { medianTurnaroundHours: 2.4, p95TurnaroundHours: 9.8, medianActiveProcessingHours: 0.2, p95ActiveProcessingHours: 0.9, medianReviewWaitHours: 0.6,
      medianReviewHandlingHours: 0.1, turnaroundSamples: 463, activeProcessingSamples: 463, reviewWaitSamples: 148, reviewHandlingSamples: 148 },
    backlog: { underOneHour: 10, oneToFourHours: 6, fourToTwentyFourHours: 4, oneToThreeDays: 3, overThreeDays: 0, oldestHours: 30.5 },
    usage: { packages: 512, documents: 1873, pages: 14220, averageDocumentsPerPackage: 3.7, averagePagesPerPackage: 27.8, reviewedPackages: 148, straightThroughPackages: 348, reprocessedPackages: 5 },
    serviceLevel: {
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, deliveryWithinTargetRate: 91, deliveryEligible: 463, deliveryBreaches: 42,
      straightThroughTargetPercent: 70, straightThroughActualPercent: 68,
      reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, reviewWithinTargetRate: 83, reviewEligible: 148, reviewBreaches: 25,
    },
    dimensions: [{ key: 'property_state', label: 'Property state', values: [{ value: 'CA', batches: 300, delivered: 280, reviewRate: 0.3 }, { value: 'NV', batches: 212, delivered: 183, reviewRate: 0.25 }] }],
    timeSaved: { casesDelivered: 512, casesNoPerson: 348, percentNoPerson: 67, pagesChecked: 14220, documentsChecked: 1873, itemsRedacted: 3912,
      itemTypes: ['Social Security Number', 'Date of birth', 'Credit card number', 'Credit score'], pagesNoPerson: 9690, minutesPerPage: 1, minutesSaved: 9690 },
  };
}

/* ---------- Admin › Redaction & retention, Case fields ---------- */

export function configuration() {
  return {
    identifiers: [
      { id: 'd1', key: 'loan_number', label: 'Loan number', required: true, primary: true, searchable: true, extractable: true, displayable: true, displayOrder: 1,
        validationType: 'TEXT', customPattern: '[A-Za-z0-9]+', exactLength: null, minLength: 6, maxLength: 12, caseNormalization: 'UPPER', stripSeparators: true, allowedValues: [] },
      { id: 'd2', key: 'borrower_name', label: 'Borrower name', required: false, primary: false, searchable: true, extractable: true, displayable: true, displayOrder: 2,
        validationType: 'TEXT', exactLength: null, minLength: null, maxLength: 120, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [], customPattern: null },
      { id: 'd3', key: 'property_state', label: 'Property state', required: false, primary: false, searchable: false, extractable: false, displayable: true, displayOrder: 3,
        validationType: 'DROPDOWN', exactLength: null, minLength: null, maxLength: null, caseNormalization: 'UPPER', stripSeparators: false, allowedValues: ['CA', 'NV', 'AZ', 'OR'], customPattern: null },
    ],
    dashboardColumns: [
      { key: 'identifier:loan', label: 'Loan number', kind: 'IDENTIFIER', primary: true, allowed: true, defaultVisible: true, displayOrder: 0 },
      { key: 'received', label: 'Received', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 1 },
      { key: 'lastUpdated', label: 'Last Updated', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: false, displayOrder: 2 },
    ],
    sensitiveDataPolicy: { enabledEntityTypes: ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE'], autoRedactThreshold: 0.8, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true },
    lifecyclePolicy: { originalRetentionHours: 72, redactedCacheHours: 24, neverExportedHoldHours: 168, finalApprovalRequired: true, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 72 },
    documentIntelligence: {
      mode: 'OBSERVE', activeBundleVersion: '2026.11.1', lastKnownGoodBundleVersion: 'r11-baseline-v1', configurationVersion: 4, baselineDiscoveryRequired: false,
      availableBundles: [
        { version: '2026.11.1', status: 'PROMOTED', modelVersion: 'qwen3.5:9b', promptVersion: 'p7', detectorVersion: 'd3', ocrRulesVersion: 'o2', profileVersion: 'none',
          validationRulesVersion: 'v5', thresholdsVersion: 't4', description: 'Improved reading of deeds of trust.', promotedAt: '2026-11-01T15:00:00Z' },
        { version: 'r11-baseline-v1', status: 'PROMOTED', modelVersion: 'qwen3.5:4b', promptVersion: 'r11', detectorVersion: 'r11', ocrRulesVersion: 'r11', profileVersion: 'none',
          validationRulesVersion: 'r11', thresholdsVersion: 'r11', description: 'Frozen baseline.', promotedAt: '2026-10-02T15:00:00Z' },
      ],
    },
    reportingConfiguration: {
      dimensions: [{ definitionId: 'd3', key: 'property_state', label: 'Property state', enabled: true, displayOrder: 1 }],
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70, reviewWaitTargetMinutes: 60, reviewWaitTargetPercent: 80, minutesPerPage: 1,
    },
  };
}
const ESTIMATE = {
  windowDays: 30, cases: 512, enoughData: true,
  current: { reviewMode: 'UNCERTAIN_ONLY', threshold: 0.8, preset: 'BALANCED', casesToPerson: 148, findingsToPerson: 340 },
  proposed: { reviewMode: 'UNCERTAIN_ONLY', threshold: 0.7, preset: 'LIGHT', casesToPerson: 96, findingsToPerson: 205 },
};

/* ---------- Admin › Integrations, Sign-in ---------- */

const connection = (over: Record<string, unknown>) => ({
  connectionId: 'c-x', tenantId: ENTITY.tenantId, name: 'Connection', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'HTTP_API',
  authenticationType: 'OAUTH2_CLIENT_CREDENTIALS', clientPrincipal: null, secretConfigured: false, endpoint: null, fileShareRoot: null,
  pollingScheduleType: 'NONE', pollingIntervalMinutes: null, pollingDailyTime: null,
  pollingTimeZone: null, pollingBatchLimit: null, acknowledgementMode: 'NONE', enabled: true, configurationVersion: 3, healthStatus: 'HEALTHY',
  lastSuccessAt: null, lastFailureAt: null, lastFailureCategory: null, createdAt: minsAgo(9000), updatedAt: minsAgo(9000), ...over,
});
const CONNECTIONS = [
  connection({ connectionId: 'c-los', name: 'Loan origination system', clientPrincipal: 'los-service', lastSuccessAt: minsAgo(14) }),
  connection({ connectionId: 'c-poll', name: 'Servicing outbox', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'API_KEY', secretConfigured: true,
    endpoint: 'https://servicing.northwind.example/api/outbox', pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 15, pollingBatchLimit: 100,
    acknowledgementMode: 'SOURCE_PICKUP_ACK', healthStatus: 'DEGRADED', lastSuccessAt: minsAgo(60), lastFailureAt: minsAgo(5), lastFailureCategory: 'POLL_SOURCE_UNAVAILABLE' }),
  connection({ connectionId: 'c-store', name: 'Document store', purpose: 'DOCUMENT_STORE_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'OAUTH2_CLIENT_CREDENTIALS',
    secretConfigured: true, endpoint: 'https://store.northwind.example/api', acknowledgementMode: 'SUBMISSION_ACK', lastSuccessAt: minsAgo(30) }),
  connection({ connectionId: 'c-title', name: 'Title team folder', purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'FILE_SHARE',
    fileShareRoot: '\\\\files01\\title\\inbound', acknowledgementMode: 'DURABLE_FILE_WRITE', enabled: false, healthStatus: 'DISABLED' }),
];
const ATTENTION = [{
  derivedDeliveryId: 'dd-1', packageId: REVIEW_CASE.packageId, primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: 'LN204812', sourceDocumentId: 'doc-1', exportCycle: 1,
  destinationLabel: 'Title pages', markerType: 'BARCODE', markerValue: 'TITLE-001', pageNumbers: [3, 4], fileName: 'title-pages.pdf', status: 'ATTENTION_REQUIRED',
  attemptCount: 5, failureCategory: 'DELIVERY_IO', retryable: true,
}];
const IDENTITY = {
  active: {
    connectionId: 'idc-1', tenantId: ENTITY.tenantId, providerId: 'customer-oidc', providerType: 'OIDC', displayName: 'Northwind Entra ID',
    issuerUri: 'https://login.northwind.example/tenant/v2.0', browserClientId: 'lexavault-web', apiAudience: 'lexavault-api', status: 'ACTIVE',
    configurationVersion: 4, createdAt: minsAgo(90000), updatedAt: minsAgo(9000), lastTestedAt: minsAgo(9100), lastTestStatus: 'PASSED', lastTestMessage: 'Discovery OK',
  },
  draft: null,
};

/* ---------- Admin › Platform operations ---------- */

const OVERVIEW = {
  generatedAt: minsAgo(1),
  providers: [
    { key: 'database', label: 'PostgreSQL', category: 'Database', provider: 'postgresql', status: 'HEALTHY', configured: true, detail: 'Connection valid' },
    { key: 'ocr', label: 'OCR', category: 'OCR', provider: 'tesseract', status: 'HEALTHY', configured: true, detail: 'Language: eng' },
    { key: 'delivery', label: 'Final delivery', category: 'Delivery', provider: 'document-store', status: 'DEGRADED', configured: true, detail: 'Tenant document store connection configured; secrets are protected' },
  ],
  watchedFolder: {
    enabled: true, schedulingEnabled: true, scanIntervalMs: 60000, rootPath: 'C:\\LexaVault\\inbound', triggerFileName: 'READY', incompleteVisibilityMinutes: 60,
    acceptedBehavior: 'MOVE', rejectedBehavior: 'MOVE', processingDirectory: 'C:\\LexaVault\\processing', coordinationEnabled: true,
    nodeId: 'node-a', scannerRole: 'LEADER', leaseOwner: 'node-a/123', fencingToken: 42, leaseExpiresAt: minsAgo(-1), takeoverCount: 2,
    lastScanAt: minsAgo(1), lastScanOutcome: 'SUCCESS', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 },
  },
  resilience: { maxAttempts: 3, initialBackoffMs: 1000, packageProcessingMaxAttempts: 3, aiSecondaryEnabled: false, aiSecondaryProvider: 'none', aiSecondaryModel: '' },
  integrations: { deliveryType: 'document-store', documentStoreConfigured: true, identityMode: 'OIDC', productionIdentityConfigured: true },
  technicalExceptions: [],
};
const TENANTS = [{ tenantId: 't-nw', name: 'Northwind Commercial', slug: 'nwc', status: 'ACTIVE' }, { tenantId: 't-co', name: 'Contoso Lending', slug: 'col', status: 'ACTIVE' }];
const STORAGE = {
  ready: true,
  deploymentDefault: { id: 'as-1', providerId: 'p-ssd', scope: 'DEPLOYMENT_DEFAULT', activatedAt: minsAgo(9000) },
  tenantOverrides: [{ id: 'as-2', tenantId: 't-nw', providerId: 'p-nas', scope: 'TENANT_OVERRIDE', activatedAt: minsAgo(900) }],
  providers: [
    { id: 'p-ssd', name: 'Local SSD', providerType: 'SHARED_FILESYSTEM', rootPath: 'D:\\lexavault\\work', status: 'AVAILABLE', configurationVersion: 1 },
    { id: 'p-nas', name: 'Shared NAS', providerType: 'SHARED_FILESYSTEM', rootPath: '\\\\nas01\\lexavault', status: 'AVAILABLE', configurationVersion: 2 },
    { id: 'p-san', name: 'New SAN volume', providerType: 'SHARED_FILESYSTEM', rootPath: '\\\\san02\\lv', status: 'CANDIDATE', configurationVersion: 1 },
  ],
  nodeValidations: [
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-ssd', status: 'HEALTHY', failureCode: null, checkedAt: minsAgo(2) },
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-nas', status: 'HEALTHY', failureCode: null, checkedAt: minsAgo(2) },
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-san', status: 'UNHEALTHY', failureCode: 'ROOT_NOT_WRITABLE', checkedAt: minsAgo(2) },
  ],
  tenants: TENANTS,
  activeNodeCount: 1,
};
const DEPLOYMENT_BRAND = { id: 'b-1', tenantId: null, applicationName: 'LexaVault', shortName: null, organizationName: 'Northwind Title & Escrow', pageTitle: null,
  secondaryColor: null, primaryLogoDataUrl: null, compactLogoDataUrl: null, faviconDataUrl: null, updatedBy: 'ops', updatedAt: minsAgo(500) };
const BRANDING = { deployment: DEPLOYMENT_BRAND, tenants: [{ tenantId: 't-nw', tenantName: 'Northwind Commercial', tenantSlug: 'nwc', customBranding: false }] };
const QUEUE = { staged: true, allEntities: true, windowMinutes: 10, etaSeconds: 2400, stages: [
  { stage: 'TEXT', waiting: 120, running: 4, held: 0, toCome: 0, workers: 4, highest: 6, oldestWaitingSeconds: 310, perMinute: 40, etaSeconds: 190 },
  { stage: 'FIND', waiting: 84, running: 5, held: 0, toCome: 124, workers: 5, highest: 5, oldestWaitingSeconds: 540, perMinute: 5.3, etaSeconds: 2400 },
  { stage: 'VISION', waiting: 0, running: 0, held: 0, toCome: 0, workers: 1, highest: 2, oldestWaitingSeconds: null, perMinute: null, etaSeconds: 0 },
  { stage: 'REDACT', waiting: 2, running: 1, held: 0, toCome: 210, workers: 1, highest: 6, oldestWaitingSeconds: 4, perMinute: 30, etaSeconds: 2400 },
  { stage: 'EXPORT', waiting: 0, running: 0, held: 3, toCome: 22, workers: 1, highest: 8, oldestWaitingSeconds: null, perMinute: 4, etaSeconds: 2400 }] };
const CAPACITY = { staged: true, queueLength: 420, intakeSlowdown: 4, intakeStopped: false, aiMaximum: null,
  stages: ['TEXT', 'FIND', 'VISION', 'REDACT', 'EXPORT'].map((stage) => ({ stage, current: 3, busy: 2, lowest: 1, highest: 7, measured: 7, maximum: null, waiting: 84 })),
  aiServers: [{ number: 1, requestsAtOnce: 5, maximum: 64, learned: true }] };
const TELEMETRY = { mode: 'BASIC', enabled: true, installationIdConfigured: true, privacyContract: 'ALLOW_LIST_ONLY',
  eventAllowList: ['package.processing.attempt', 'package.processing.finished'], prohibitedData: ['document/PDF/image content', 'actor identities'] };
/** Task 0057: Support, Status. */
export const SUPPORT_STATUS = {
  overall: 'DOWN',
  checks: [
    { name: 'database', group: 'platform', state: 'WORKING', code: 'MON_WORKING', cause: null, whatToDo: null, required: true, checkedAt: minsAgo(1) },
    { name: 'licence', group: 'platform', state: 'HAVING_PROBLEMS', code: 'MON_LICENCE_EXPIRING', cause: 'The licence ends on 2026-12-20, in 26 days.', whatToDo: 'Ask your account manager for the renewed licence and store it with the licence command before that date.', required: false, checkedAt: minsAgo(1) },
    { name: 'storage', group: 'services', state: 'WORKING', code: 'MON_WORKING', cause: null, whatToDo: null, required: true, checkedAt: minsAgo(1) },
    { name: 'service-model-server', group: 'services', state: 'DOWN', code: 'MON_HTTP_UNREACHABLE', cause: 'The service model-server does not answer at http://ai-1.example:11434.', whatToDo: 'Check that the service runs and that this server may reach its address.', required: false, checkedAt: minsAgo(1) },
  ],
  failures: [{ at: minsAgo(4), code: 'LV-AI-001', reference: 'R-0J5K2Q7M9X4D', cause: "The AI model couldn't be reached on any server.", whatToDo: 'Check that the AI server is running.' }],
};

/** Task 0057: the Licensing read (Business Admin). */
export const LICENSING = {
  intakeAllowed: true, stopped: null,
  licence: { licenceId: 'LIC-NW-0001', version: 2, customer: 'Northwind Title & Escrow', type: 'SUBSCRIPTION', kind: 'NON_PRODUCTION', testEnvironment: true,
    state: 'VALID', message: 'The licence is valid until 2027-06-30.', startsOn: '2026-07-01', expiresOn: '2027-06-30', updatesUntil: null,
    daysUntilExpiry: 218, graceEndsOn: '2027-07-30', clockMessage: null, waiting: null },
  entity: { slotId: 'ENT-NW-0001', month: '2026-11', allowances: [{ type: 'PAGES_PER_MONTH', unit: 'pages', used: 412, limit: 1000, level: 'WITHIN' }] },
};
export const LICENSING_STOPPED = {
  ...LICENSING, intakeAllowed: false,
  stopped: { code: 'LV-LIC-002', title: 'Licence expired', platformCode: 'EXPIRED_GRACE_OVER' },
  licence: { ...LICENSING.licence, kind: 'PRODUCTION', testEnvironment: false, state: 'EXPIRED_GRACE_OVER', expiresOn: '2026-10-01', graceEndsOn: '2026-10-31', daysUntilExpiry: -54,
    message: 'The licence expired on 2026-10-01 and the grace period ended on 2026-10-31. Intake is stopped; viewing and export still work. Install a renewed licence to resume intake.' },
  entity: { ...LICENSING.entity, allowances: [{ type: 'PAGES_PER_MONTH', unit: 'pages', used: 1204, limit: 1000, level: 'OVER_LIMIT' }] },
};

/* ---------- Audit trail ---------- */

const DANA = 'a1b2c3d4-0000-4000-8000-000000000001';
const rec = (n: number, m: number, action: string, actorId: string, resourceType: string, resourceId: string, outcome: string, detail: string) =>
  ({ id: `r-${n}`, actorId, action, resourceType, resourceId, outcome, correlationId: `c0ffee00-0000-4000-8000-00000000000${n}`, detail, occurredAt: minsAgo(m) });
const RECORDS = [
  rec(1, 5, 'SENSITIVE_DATA_POLICY_CHANGED', DANA, 'SensitiveDataPolicy', ENTITY.tenantId, 'SUCCESS', 'Sensitive-data policy changed: enabledCount=4, threshold=0.85, reviewMode=FOCUSED, maskSsnLastFour=true'),
  rec(2, 40, 'PACKAGE_FINAL_APPROVED', DANA, 'DocumentPackage', APPROVAL_CASE.packageId, 'SUCCESS', 'Final delivery approved'),
  rec(3, 80, 'FINDING_CONFIRMED', '00000000-0000-0000-0000-000000000000', 'Finding', 'f-1', 'SUCCESS', 'Finding confirmed: type=SSN'),
  rec(4, 200, 'ENTERPRISE_IDENTITY_CONNECTION_TESTED', DANA, 'EnterpriseIdentityConnection', 'eic-1', 'DENIED', 'Test older than 15 minutes'),
];

/* ---------- Support ---------- */

export const INCIDENT = 'e9e9e9e9-0000-4000-8000-000000000001';
const summary = (n: number, loan: string, reasonCode: string, stage: string, status: string, businessImpact: string, attemptCount: number, m: number) => ({
  incidentId: n === 1 ? INCIDENT : `e9e9e9e9-0000-4000-8000-00000000000${n}`, tenantId: ENTITY.tenantId, packageId: n === 1 ? STOPPED_CASE.packageId : id(`010${n}`),
  documentId: DOC, primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: loan, stage, component: 'worker', reasonCode, status,
  businessOutcome: businessImpact === 'COMPLETED' ? 'COMPLETED' : null, businessContinuityUsed: businessImpact !== 'BLOCKED', retryable: true,
  attemptCount, correlationId: `c0ffee00-0000-4000-8000-00000000000${n}`, businessImpact, firstObservedAt: minsAgo(m + 20), lastObservedAt: minsAgo(m),
  resolvedAt: status === 'RESOLVED' ? minsAgo(m) : null,
});
const INCIDENTS = [
  summary(1, 'LN-3003', 'OCR_TIMEOUT', 'OCR', 'OPEN', 'BLOCKED', 3, 180),
  summary(2, 'LN204864', 'DESTINATION_UNAVAILABLE', 'DELIVERY', 'BUSINESS_CONTINUED', 'CONTINUED', 1, 185),
  summary(3, 'LN204867', 'AI_TIMEOUT', 'AI_ANALYSIS', 'RESOLVED', 'COMPLETED', 1, 60),
];
const INCIDENT_DETAIL = {
  ...INCIDENTS[0], packageStatus: 'FAILED', failureCategory: 'DEPENDENCY_TIMEOUT', safeSummary: 'OCR did not complete within the allowed time.',
  artifactAvailability: 'RETAINED_UNTIL_POLICY_EXPIRY', technicalAttentionRetainedUntil: new Date(Date.now() + 2 * 864e5).toISOString(), artifactsPurgedAt: null,
  processingFingerprint: { bundle: '2026.11.1', ocr: 'tesseract-5.4' }, safeMetadata: { page: '14' },
  events: [
    { eventType: 'FAILURE_RECORDED', attemptNumber: 1, scope: 'DOCUMENT', reasonCode: 'OCR_TIMEOUT', retryable: true, correlationId: 'corr-91', occurredAt: minsAgo(200), safeMetadata: {} },
    { eventType: 'PROCESSING_RECOVERY_EXHAUSTED', correlationId: 'corr-93', occurredAt: minsAgo(179), safeMetadata: {} },
  ],
};
const PNG_1PX = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

/* ---------- The API ---------- */

export interface MockOptions {
  /** Task 0059: Admin › Notifications as the IT Admin sees it. */
  notificationsIt?: object;
  /** Paths (without /api/v1) that answer 500, to show a screen's problem card. */
  broken?: string[];
  /** Two entities: the chooser shows after sign-in. */
  twoEntities?: boolean;
  /** Task 0029: the export screens' data (cases in every export state, refused cases, IT groups, the alert). */
  exports?: boolean;
  /** Task 0057: the Licensing read. */
  licensing?: unknown;
  /** Task 0058: the entity's time zone. */
  timeZone?: string;
  /** Task 0058: Admin › Sign-in. */
  identity?: unknown;
}

/** Task 0058: Admin › Entities. */
export const ENTITY_SLOTS = {
  entities: [
    { slotId: 'ENT-LEX-0001', name: 'Residential lending', status: 'ACTIVE', current: true, timeZone: null, customerTimeZone: 'America/Chicago', activatedAt: '2026-01-05T09:00:00Z' },
    { slotId: 'ENT-LEX-0002', name: 'Commercial lending', status: 'ACTIVE', current: false, timeZone: 'Europe/London', customerTimeZone: 'America/Chicago', activatedAt: '2026-02-05T09:00:00Z' },
    { slotId: 'ENT-LEX-0003', name: null, status: 'NOT_ACTIVATED', current: false, timeZone: null, customerTimeZone: 'America/Chicago', activatedAt: null },
    { slotId: 'ENT-LEX-0004', name: 'Northwind Trust', status: 'SUSPENDED', current: false, timeZone: null, customerTimeZone: 'America/Chicago', activatedAt: '2026-03-05T09:00:00Z' },
  ],
};

/* ---------- Task 0059: notifications and the approval link ---------- */

export const APPROVAL_TOKEN = 'AQEBfaketokenfaketokenfaketoken.c2lnbmF0dXJlc2lnbmF0dXJl';
export const APPROVAL_READY = { state: 'READY', action: 'APPROVE', caseId: APPROVAL_CASE.packageId, caseNumber: 'LN-3002', askedBy: 'Maria Alvarez', expiresAt: '2027-01-15T10:00:00Z', code: null, message: '' };
const NOTIFICATION_PEOPLE = [{ role: 'TENANT_IT_ADMIN', people: 2 }, { role: 'BUSINESS_ADMIN', people: 1 }, { role: 'DOCUMENT_SUPERVISOR', people: 4 }, { role: 'DOCUMENT_PROCESSOR', people: 11 }];
const PLAIN = { dailyAt: null, alertAfterMinutes: null, repeatMinutes: null, alertAfterMax: 1440, thresholds: [] };
const LASTING = { ...PLAIN, alertAfterMinutes: 15, repeatMinutes: 240 };
const critical = (id: string, name: string) => ({ ...LASTING, alertAfterMax: 60, id, name, enabled: true, alwaysOn: true, frequency: 'IMMEDIATELY', frequencies: ['IMMEDIATELY', 'EVERY_HOUR'], roles: ['TENANT_IT_ADMIN'] });
const ALL_FREQUENCIES = ['IMMEDIATELY', 'EVERY_HOUR', 'EVERY_4_HOURS', 'ONCE_A_DAY'];
export const NOTIFICATIONS_IT = {
  mail: { enabled: true, mode: 'MICROSOFT_365', host: null, port: null, security: null, username: null, fromAddress: 'lexavault@northwind.example', fromName: 'LexaVault', directoryId: '00000000-0000-0000-0000-000000000001', clientId: '00000000-0000-0000-0000-000000000002', credentialType: 'CERTIFICATE', secretNamed: true },
  appAddressSet: true, approvalLinksOn: true, secretNamePrefix: 'LEXAVAULT_SECRET_C9C9C9C900004000800000000000000C_', mailPorts: [25, 465, 587, 2525],
  people: NOTIFICATION_PEOPLE,
  events: [critical('export.destination-paused', 'Destination paused'), critical('export.destination-needs-it', 'Destination needs IT'), critical('export.destinations-unreachable', 'Destinations can not be reached'), critical('export.cases-need-it', 'Cases waiting for IT'),
    { ...LASTING, alertAfterMinutes: 30, id: 'export.destination-busy', name: 'Destination busy for more than 30 minutes', enabled: true, alwaysOn: false, frequency: 'IMMEDIATELY', frequencies: ALL_FREQUENCIES, roles: ['TENANT_IT_ADMIN'] },
    critical('storage.processing-stopped', 'Processing stopped, storage not usable'), critical('system.ai-model-down', 'AI model down'), critical('system.key-service-down', 'Key service down or refusing'), critical('system.erase-log-stop', 'Erase log not usable, or a restore waiting for its replay'), critical('system.disk-almost-full', 'Disk almost full'),
    { ...LASTING, id: 'intake.source-unreachable', name: 'Intake source can not be reached', enabled: true, alwaysOn: false, frequency: 'IMMEDIATELY', frequencies: ALL_FREQUENCIES, roles: ['TENANT_IT_ADMIN'] },
    { ...LASTING, thresholds: [{ name: 'failuresInAnHour', label: 'Problems in an hour', value: 3, min: 1, max: 1000 }], id: 'processing.repeated-failures', name: 'Repeated processing failures', enabled: true, alwaysOn: false, frequency: 'IMMEDIATELY', frequencies: ALL_FREQUENCIES, roles: ['TENANT_IT_ADMIN'] },
    { ...PLAIN, id: 'intake.files-rejected', name: 'File rejected at intake', enabled: true, alwaysOn: false, frequency: 'EVERY_4_HOURS', frequencies: ALL_FREQUENCIES, roles: ['TENANT_IT_ADMIN'] }],
  recent: [{ event: 'Destination paused', state: 'SENT', code: null, reason: null, at: '2026-11-24T14:05:00Z' }, { event: 'Test message', state: 'FAILED', code: 'MAIL_SMTP_AUTH_DISABLED', reason: 'SMTP AUTH is disabled for the sender mailbox', at: '2026-11-24T13:40:00Z' }],
};
export const NOTIFICATIONS_BUSINESS = {
  mailOn: true, people: NOTIFICATION_PEOPLE,
  events: [
    { ...LASTING, alertAfterMinutes: 0, id: 'work.review-waiting', name: 'Cases waiting for review', enabled: true, alwaysOn: false, frequency: 'EVERY_4_HOURS', frequencies: ALL_FREQUENCIES, roles: ['DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR'] },
    { ...PLAIN, dailyAt: '08:00', id: 'licensing.licence-expiring', name: 'Licence expiring', enabled: true, alwaysOn: false, frequency: 'ONCE_A_DAY', frequencies: ALL_FREQUENCIES, roles: ['BUSINESS_ADMIN'] },
    { ...LASTING, alertAfterMinutes: 0, alertAfterMax: 60, id: 'licensing.intake-stopped', name: 'Intake stopped, licence expired', enabled: true, alwaysOn: true, frequency: 'IMMEDIATELY', frequencies: ['IMMEDIATELY', 'EVERY_HOUR'], roles: ['BUSINESS_ADMIN', 'TENANT_IT_ADMIN'] },
    { ...PLAIN, dailyAt: '08:00', thresholds: [{ name: 'nearLimitPercent', label: 'Warn at percent used', value: 80, min: 50, max: 99 }], id: 'licensing.usage-near-limit', name: 'Usage near limit', enabled: true, alwaysOn: false, frequency: 'ONCE_A_DAY', frequencies: ALL_FREQUENCIES, roles: ['BUSINESS_ADMIN'] },
    { ...PLAIN, id: 'approval.delivery-waiting', name: 'Case waiting for delivery approval', enabled: true, alwaysOn: false, frequency: 'IMMEDIATELY', frequencies: ['IMMEDIATELY'], roles: ['DOCUMENT_SUPERVISOR'] },
  ],
};

export async function mockApi(page: Page, who: Who, options: MockOptions = {}) {
  const roles = [...ROLES[who]];
  const capabilities = roles.length ? capabilitiesFor(...roles) : [];
  const entities = options.twoEntities ? [{ ...ENTITY, roles }, { ...OTHER_ENTITY, roles }] : [{ ...ENTITY, roles }];
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const method = request.method();
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (options.broken?.includes(path)) return json({ message: 'Something went wrong', correlationId: 'c0ffee00-0000-4000-8000-0000000000ff' }, 500);

    if (path === '/me/tenants') return json(entities);
    if (path === '/me') return json({ username: `${who}.user@example.test`, roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities, timeZone: options.timeZone ?? null });
    if (path.startsWith('/branding')) return json({});

    // Cases, the case panel, Approve
    // Task 0029: the export answers; inline mode's empty answers unless the test asks for the export screens.
    const ex = options.exports ? EXPORTS : null;
    if (path === '/export-jobs/waiting-for-you') return json(ex ? ex.waiting : INLINE_EXPORT.waiting);
    if (path === '/support/export-jobs/needs-it') return json(ex ? ex.needsIt : INLINE_EXPORT.needsIt);
    if (path === '/support/export-destinations') return json(ex ? ex.destinations : INLINE_EXPORT.destinations);
    if (path === '/support/export-destinations/alert') return json(ex ? ex.alert : INLINE_EXPORT.alert);
    if (/^\/packages\/[^/]+\/export-jobs$/.test(path)) return json(ex ? ex.caseExport(path.split('/')[2]) : INLINE_EXPORT.caseExport);
    if (path === '/business-admin/export-retention') return json(retentionSettings());
    if (path === '/packages' && method === 'GET') return json(ex ? [...EXPORT_CASES, ...CASES] : CASES);
    const one = [...(ex ? EXPORT_CASES : []), ...CASES].find((c) => path === `/packages/${c.packageId}`);
    if (one) return json(one);
    if (path.endsWith('/lifecycle')) return json({ packageId: path.split('/')[2], successfulExportCycles: 0, maximumExportCycles: 3, canReprocess: true, finalApprovalRequired: true, finalApprovalGranted: false });
    if (path.endsWith('/derived-deliveries') && method === 'GET') return json([]);
    if (path.endsWith('/manual-fallback/eligibility')) return json({ eligible: true, reason: '' });
    if (path === '/intake/identifier-definitions') return json(configuration().identifiers);

    // Review
    if (path === '/review-items') return json({ totalItems: FINDINGS.length, items: FINDINGS, autoRedactThreshold: 0.8 });
    if (path.endsWith('/page-preview')) return route.fulfill({ status: 200, contentType: 'image/png', headers: { 'X-Review-Page-Number': '1' }, body: PAGE_PNG });

    // Continue manually
    if (path === `/packages/${STOPPED_CASE.packageId}/manual-fallback/start`) return json({ packageId: STOPPED_CASE.packageId, status: 'MANUAL_FALLBACK', documents: [], regions: [], enabledEntityTypes: [] });
    if (path === `/packages/${STOPPED_CASE.packageId}/manual-fallback`) {
      return json({ packageId: STOPPED_CASE.packageId, status: 'MANUAL_FALLBACK', documents: [{ documentId: DOC, fileName: 'Title_Commitment.pdf', pageCount: 3 }],
        regions: [{ findingId: 'm-1', documentId: DOC, pageNumber: 1, entityType: 'US_SSN', left: 0.2, top: 0.3, width: 0.2, height: 0.03 }], enabledEntityTypes: ['US_SSN', 'DATE_OF_BIRTH'] });
    }
    if (path.endsWith('/preview')) return route.fulfill({ status: 200, contentType: 'image/png', headers: { 'X-Manual-Page-Number': '1' }, body: PAGE_PNG });

    // Reports
    if (path === '/operations/reports') return json(reports());

    // Admin › Redaction & retention, Case fields
    if (path === '/business-admin/configuration') return json(configuration());
    if (path === '/business-admin/sensitive-data-policy/estimate') return json(ESTIMATE);

    // Admin › Integrations, Sign-in
    if (path === '/integrations' && method === 'GET') return json(CONNECTIONS);
    if (path === '/derived-deliveries/attention') return json(ATTENTION);
    if (path === '/business-admin/routing-rules') {
      return json([{ ruleId: 'r1', destinationLabel: 'Title pages', markerType: 'BARCODE', markerValue: 'TITLE-001', scanRegion: 'TOP_RIGHT', destinationConnectionId: 'c-title', enabled: true }]);
    }
    if (path === '/business-admin/routing-rules/destinations') return json([{ connectionId: 'c-title', name: 'Title team folder', providerType: 'FILE_SHARE', enabled: false, healthStatus: 'DISABLED' }]);
    if (path === '/integrations/c-poll/polling') return json({ connectionId: 'c-poll', name: 'Servicing outbox', enabled: true, healthStatus: 'DEGRADED', nextScheduledAt: minsAgo(-10), recentRuns: [] });
    if (path === '/admin/enterprise-identity') return json(options.identity ?? IDENTITY);

    // Admin › Platform operations
    if (path === '/platform-admin/overview' || path === '/platform-admin/health-check') return json(OVERVIEW);
    if (path === '/platform-admin/capacity') return json(CAPACITY);
    if (path === '/platform-admin/queue') return json(QUEUE);
    if (path === '/platform/processing-storage') return json(STORAGE);
    if (path === '/platform/branding') return json(BRANDING);
    if (path === '/platform/branding/tenants/t-nw') return json({ tenantId: 't-nw', customBranding: false, configuration: null });
    if (path === '/platform/product-telemetry/status') return json(TELEMETRY);
    if (path === '/admin/entities') return json(ENTITY_SLOTS);
    if (path === '/admin/notifications/it') return json(options.notificationsIt ?? NOTIFICATIONS_IT);
    if (path === '/admin/notifications/business') return json(NOTIFICATIONS_BUSINESS);
    if (path === '/approvals/link/open') return json(APPROVAL_READY);
    if (path === '/licensing') return json(options.licensing ?? LICENSING);
    if (path === '/support/status') return json(SUPPORT_STATUS);

    // Audit trail
    if (path === '/audit-records/count') return json({ count: RECORDS.length });
    if (path === '/audit-records') return json(RECORDS);
    // Task 0041: the grant history (sign-in access changes), read-only.
    if (path === '/audit-records/grant-history') return json([
      { changedAt: minsAgo(20), change: 'ADDED', group: 'BusinessUsers', roleBefore: null, roleAfter: 'DOCUMENT_PROCESSOR',
        source: 'SETUP', changedBy: 'platform-setup', setupRunId: 'run-1', issuer: 'https://login.example/realms/nw' },
    ]);

    // Support and the problem page
    if (path === '/support/incidents') return json({ items: INCIDENTS, total: INCIDENTS.length, page: 0, size: 25 });
    if (path === `/support/incidents/${INCIDENT}`) return json(INCIDENT_DETAIL);
    if (path === `/technical-exceptions/${STOPPED_CASE.packageId}`) {
      return json({ packageId: STOPPED_CASE.packageId, documentId: DOC, status: 'ATTENTION_REQUIRED', failureCategory: 'DEPENDENCY_TIMEOUT', failedStage: 'OCR', retryable: true,
        failedAt: minsAgo(180), correlationId: 'corr-93', originalRetainedUntil: INCIDENT_DETAIL.technicalAttentionRetainedUntil, explanation: 'OCR did not complete.',
        incidentId: INCIDENT, incidentStatus: 'OPEN', businessContinuityUsed: false, businessOutcome: null, processingFingerprint: {}, incidentEvents: [], operatorDiagnostics: null });
    }
    if (path.endsWith('.png')) return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1PX });
    return json({});
  });
}

/** Signs in as `who` (choosing the first entity when there are two) and opens `path` inside the app. */
export async function signInAs(page: Page, who: Who, path = '/', options: MockOptions = {}) {
  await mockIdentityProvider(page);
  await mockApi(page, who, options);
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  if (options.twoEntities) await page.getByRole('button', { name: ENTITY.name }).click();
  await page.locator('#cf-main h1').first().waitFor();
  if (path !== '/') await go(page, path);
}

/** Moves inside the single-page app without a reload, the way a link does. */
export async function go(page: Page, path: string) {
  await page.evaluate((p) => { window.history.pushState({}, '', p); window.dispatchEvent(new PopStateEvent('popstate')); }, path);
}

export async function expectVisible(page: Page, name: string | RegExp, role: 'heading' | 'region' | 'dialog' | 'alertdialog' | 'menu' = 'heading') {
  await expect(page.getByRole(role, { name }).first()).toBeVisible();
}
