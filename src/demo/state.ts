import type {
  AuditRecordResult,
  BrandingConfiguration,
  BusinessAdminConfiguration,
  DashboardStage,
  DerivedDelivery,
  DocumentSummary,
  EnterpriseIdentityOverview,
  IntegrationConnection,
  LicenseHistoryItem,
  LicensingOverview,
  ManualFallbackRegion,
  PollingRun,
  ProcessingStorageOverview,
  ReviewBoundingBox,
  RoutingRule,
  SupportIncidentDetail,
} from '../types';
import type { ScanSession, ScannerDeliveryItem } from '../api/scanBridgeClient';

export const DEMO_TENANT_ID = '00000000-0000-0000-0000-000000000001';
export const DEMO_TENANT_NAME = 'Northwind Lending';
export const DEMO_TENANT_SLUG = 'northwind';
const STORAGE_KEY = 'lexavault.demo.state.v1';
const STATE_VERSION = 3;

export interface DemoDocument extends DocumentSummary { pageCount: number }

export interface DemoPackage {
  packageId: string;
  packageType: string;
  status: string;
  identifiers: Record<string, string>;
  documents: DemoDocument[];
  createdAt: string;
  updatedAt: string;
  successfulExportCycles: number;
  lastExportedAt: string | null;
  finalApprovalGranted: boolean;
  technicalHoldUntil: string | null;
  hidden: boolean;
  reviewed: boolean;
  reprocessed: boolean;
  processingCompleteAt: string | null;
  failureReason: string | null;
  manualFallback: { status: string; regions: ManualFallbackRegion[] } | null;
}

export interface DemoFinding {
  findingId: string;
  packageId: string;
  documentId: string;
  fileName: string;
  entityType: string;
  confidence: number;
  pageNumber: number;
  boundingBox: ReviewBoundingBox | null;
  reason: string;
  status: 'OPEN' | 'CONFIRMED' | 'DISMISSED';
}

export interface DemoState {
  version: number;
  seededAt: string;
  packages: DemoPackage[];
  findings: DemoFinding[];
  audit: AuditRecordResult[];
  integrations: IntegrationConnection[];
  pollingRuns: PollingRun[];
  routingRules: RoutingRule[];
  deliveries: DerivedDelivery[];
  incidents: SupportIncidentDetail[];
  businessAdmin: BusinessAdminConfiguration;
  brandingDeployment: BrandingConfiguration | null;
  tenantBranding: Record<string, BrandingConfiguration>;
  identity: EnterpriseIdentityOverview;
  storage: ProcessingStorageOverview;
  licensing: LicensingOverview;
  licenseHistory: Record<string, LicenseHistoryItem[]>;
  scannerSession: ScanSession | null;
  scannerDeliveries: ScannerDeliveryItem[];
}

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const iso = (ms: number) => new Date(ms).toISOString();

let counter = 0;
export function demoId(prefix = ''): string {
  counter += 1;
  const random = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `${Date.now().toString(16)}-${counter}`;
  return prefix ? `${prefix}-${random.slice(0, 8)}` : random;
}

function seededRandom(seed: number) {
  let value = seed;
  return () => {
    value = (value * 16807) % 2147483647;
    return (value - 1) / 2147483646;
  };
}

const BORROWERS = ['Alvarez', 'Chen', 'Okafor', 'Lindqvist', 'Patel', 'Moreau', 'Nakamura', 'Fitzgerald', 'Haddad', 'Kowalski', 'Reyes', 'Brennan', 'Osei', 'Varga', 'Delgado', 'Sato', 'Murphy', 'Ivanova', 'Mensah', 'Novak'];
const BRANCHES = ['Seattle', 'Portland', 'Boise', 'Spokane', 'Tacoma'];
const SOURCES = ['MANUAL_UPLOAD', 'SCANNER_INTAKE', 'FOLDER_INTAKE', 'API_SUBMISSION', 'MANUAL_UPLOAD', 'API_SUBMISSION'];
const DOC_NAMES = ['Loan_Application.pdf', 'Paystub_March.pdf', 'Bank_Statement_Q1.pdf', 'Drivers_License.pdf', 'W2_2025.pdf', 'Purchase_Agreement.pdf', 'Title_Report.pdf', 'Appraisal.pdf', 'Tax_Return_2025.pdf', 'Insurance_Binder.pdf'];
const ENTITY_TYPES = ['SSN', 'ACCOUNT_NUMBER', 'DATE_OF_BIRTH', 'DRIVERS_LICENSE', 'PHONE_NUMBER', 'EMAIL_ADDRESS', 'ADDRESS', 'PERSON_NAME'];
const REASONS = ['LOW_CONFIDENCE', 'LOCATION_UNCERTAIN', 'POLICY_REVIEW'];

export const STATUS_STAGE: Record<string, DashboardStage> = {
  SUBMITTED: 'PROCESSING',
  PROCESSING: 'PROCESSING',
  PENDING_REVIEW: 'NEEDS_REVIEW',
  IN_REVIEW: 'NEEDS_REVIEW',
  PENDING_FINAL_APPROVAL: 'NEEDS_APPROVAL',
  COMPLETED: 'EXPORTED',
  FAILED: 'TECHNICAL_ATTENTION',
  REJECTED: 'REJECTED',
};

export function stageOf(pkg: DemoPackage): DashboardStage | null {
  return STATUS_STAGE[pkg.status] ?? null;
}

const STATUS_PLAN = [
  'COMPLETED', 'COMPLETED', 'PENDING_REVIEW', 'COMPLETED', 'PROCESSING', 'PENDING_FINAL_APPROVAL', 'COMPLETED',
  'FAILED', 'COMPLETED', 'PENDING_REVIEW', 'COMPLETED', 'REJECTED', 'COMPLETED', 'PENDING_FINAL_APPROVAL',
  'COMPLETED', 'PENDING_REVIEW', 'COMPLETED', 'COMPLETED', 'FAILED', 'COMPLETED',
];

function buildPackages(now: number, rand: () => number) {
  const packages: DemoPackage[] = [];
  const findings: DemoFinding[] = [];
  for (let i = 0; i < 64; i += 1) {
    const status = i < 6 ? ['PENDING_REVIEW', 'PROCESSING', 'PENDING_FINAL_APPROVAL', 'PENDING_REVIEW', 'FAILED', 'COMPLETED'][i] : STATUS_PLAN[i % STATUS_PLAN.length];
    const ageHours = i < 6 ? 0.4 + i * 0.9 : 3 + i * 10.5 + rand() * 6;
    const created = now - ageHours * HOUR;
    const idx = String(i).padStart(3, '0');
    const packageId = `7c1e${idx}0-4b7e-4d21-9a0c-5e2f0000${idx}0`.slice(0, 36);
    const docCount = 2 + Math.floor(rand() * 6);
    const documents: DemoDocument[] = Array.from({ length: docCount }, (_, d) => {
      const pageCount = 2 + Math.floor(rand() * 12);
      return {
        documentId: `${packageId.slice(0, 8)}-d${d}`,
        fileName: DOC_NAMES[(i + d * 3) % DOC_NAMES.length],
        mimeType: 'application/pdf',
        sizeBytes: Math.round(120_000 + rand() * 2_400_000),
        documentType: 'PDF',
        status: status === 'COMPLETED' ? 'EXPORTED' : status === 'FAILED' && d === 0 ? 'FAILED' : status === 'PROCESSING' ? 'PROCESSING' : 'PROCESSED',
        pageCount,
      };
    });
    const exported = status === 'COMPLETED';
    const cycles = exported ? (i % 9 === 0 ? 2 : 1) : 0;
    const turnaround = 0.3 + rand() * (i % 5 === 0 ? 30 : 5);
    packages.push({
      packageId,
      packageType: SOURCES[i % SOURCES.length],
      status,
      identifiers: {
        contract: `LN-2026-${48213 - i * 7}`,
        borrower: BORROWERS[i % BORROWERS.length],
        branch: BRANCHES[i % BRANCHES.length],
      },
      documents,
      createdAt: iso(created),
      updatedAt: iso(exported ? created + turnaround * HOUR : created + 0.2 * HOUR),
      successfulExportCycles: cycles,
      lastExportedAt: exported ? iso(created + turnaround * HOUR) : null,
      finalApprovalGranted: exported,
      technicalHoldUntil: status === 'FAILED' ? iso(now + 3 * DAY) : null,
      hidden: false,
      reviewed: exported && i % 3 === 0,
      reprocessed: cycles > 1,
      processingCompleteAt: status === 'PROCESSING' ? iso(now + 45_000) : null,
      failureReason: status === 'FAILED' ? (i % 2 ? 'OCR_ENGINE_TIMEOUT' : 'PDF_STRUCTURE_INVALID') : status === 'REJECTED' ? 'UNREADABLE_SOURCE' : null,
      manualFallback: null,
    });
    if (status === 'PENDING_REVIEW') {
      const count = 2 + Math.floor(rand() * 3);
      for (let f = 0; f < count; f += 1) {
        const doc = documents[f % documents.length];
        const hasBox = f % 3 !== 2;
        findings.push({
          findingId: `${packageId.slice(0, 8)}-f${f}`,
          packageId,
          documentId: doc.documentId,
          fileName: doc.fileName || 'Document.pdf',
          entityType: ENTITY_TYPES[(i + f) % ENTITY_TYPES.length],
          confidence: Math.round((0.52 + rand() * 0.3) * 100) / 100,
          pageNumber: 1 + (f % doc.pageCount),
          boundingBox: hasBox ? { left: 0.12 + rand() * 0.4, top: 0.18 + f * 0.14, width: 0.22 + rand() * 0.12, height: 0.035 } : null,
          reason: hasBox ? REASONS[(i + f) % 2 === 0 ? 0 : 2] : 'LOCATION_UNCERTAIN',
          status: 'OPEN',
        });
      }
    }
  }
  // Scenario: delivered case whose internal redacted copy expires within hours.
  const expiring = packages[5];
  Object.assign(expiring, { createdAt: iso(now - 333 * HOUR), lastExportedAt: iso(now - 331 * HOUR), updatedAt: iso(now - 331 * HOUR) });
  // Scenario: a status this client does not know about must render a safe fallback with no actions.
  const unknown = packages[6];
  Object.assign(unknown, { status: 'LEGAL_HOLD', successfulExportCycles: 0, lastExportedAt: null, finalApprovalGranted: false, reviewed: false, reprocessed: false });
  unknown.documents.forEach((d) => { d.status = 'PROCESSED'; });
  return { packages, findings };
}

function buildAudit(packages: DemoPackage[]): AuditRecordResult[] {
  const actors = ['m.alvarez@northwind.example', 'j.chen@northwind.example', 'system', 'r.okafor@northwind.example', 'it.admin@northwind.example'];
  const records: AuditRecordResult[] = [];
  packages.slice(0, 40).forEach((pkg, i) => {
    const base = new Date(pkg.createdAt).getTime();
    const push = (offset: number, action: string, outcome = 'SUCCESS', detail?: string, resourceType = 'PACKAGE') => records.push({
      id: demoId(), actorId: action.startsWith('PROCESSING') || action === 'PACKAGE_EXPORTED' ? 'system' : actors[i % actors.length],
      action, resourceType, resourceId: pkg.packageId, outcome, correlationId: demoId(), detail, occurredAt: iso(base + offset),
    });
    push(0, 'PACKAGE_CREATED');
    push(60_000, 'DOCUMENT_UPLOADED', 'SUCCESS', `${pkg.documents.length} documents`, 'DOCUMENT');
    push(120_000, 'PACKAGE_SUBMITTED');
    if (pkg.status === 'FAILED') push(600_000, 'PROCESSING_FAILED', 'FAILURE', pkg.failureReason || undefined);
    if (pkg.status === 'REJECTED') push(600_000, 'PACKAGE_REJECTED', 'FAILURE', pkg.failureReason || undefined);
    if (pkg.reviewed) push(1_800_000, 'REVIEW_FINDING_CONFIRMED', 'SUCCESS', 'Reviewer confirmed sensitive content', 'FINDING');
    if (pkg.lastExportedAt) {
      push(new Date(pkg.lastExportedAt).getTime() - base - 60_000, 'PACKAGE_DELIVERY_APPROVED');
      push(new Date(pkg.lastExportedAt).getTime() - base, 'PACKAGE_EXPORTED', 'SUCCESS', 'Delivered to SharePoint · Underwriting');
    }
  });
  records.push({ id: demoId(), actorId: 'it.admin@northwind.example', action: 'INTEGRATION_UPDATED', resourceType: 'INTEGRATION', resourceId: 'int-sdv-sharepoint', outcome: 'SUCCESS', correlationId: demoId(), detail: 'Timeout changed to 45s', occurredAt: iso(Date.now() - 5 * DAY) });
  records.push({ id: demoId(), actorId: 'b.admin@northwind.example', action: 'POLICY_UPDATED', resourceType: 'TENANT_POLICY', resourceId: DEMO_TENANT_ID, outcome: 'SUCCESS', correlationId: demoId(), detail: 'Auto-redact threshold set to 0.90', occurredAt: iso(Date.now() - 8 * DAY) });
  records.push({ id: demoId(), actorId: 'unknown', action: 'AUTHENTICATION_DENIED', resourceType: 'SESSION', resourceId: 'n/a', outcome: 'DENIED', correlationId: demoId(), detail: 'Account has no LexaVault role', occurredAt: iso(Date.now() - 2 * DAY) });
  return records.sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
}

function buildIntegrations(now: number): IntegrationConnection[] {
  const base = {
    description: null, clientPrincipal: null, endpoint: null, fileShareRoot: null, secretConfigured: true,
    timeoutSeconds: 30, retryMaxAttempts: 5, incompleteWindowHours: 24, maxDocumentsPerIntake: 50,
    maxPdfSizeBytes: 50 * 1024 * 1024, maxPdfPages: 500, pollingScheduleType: null, pollingIntervalMinutes: null,
    pollingLocalTime: null, pollingTimeZone: null, pollingBatchLimit: null, enabled: true, configurationVersion: 3,
    healthStatus: 'HEALTHY', lastSuccessAt: iso(now - 0.3 * HOUR), lastFailureAt: null, lastFailureCategory: null,
    createdAt: iso(now - 90 * DAY), updatedAt: iso(now - 5 * DAY),
  } as const;
  return [
    { ...base, connectionId: 'int-push-los', name: 'Loan Origination System', description: 'Push API used by the LOS to submit closing packages', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'PUSH_API', authenticationType: 'OAUTH2_CLIENT_CREDENTIALS', clientPrincipal: 'los-prod-client', acknowledgementMode: 'SOURCE_PICKUP_ACK' },
    { ...base, connectionId: 'int-poll-servicing', name: 'Servicing document API', description: 'Polls the servicing platform for new borrower packets', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'HTTP_API', authenticationType: 'API_KEY', endpoint: 'https://servicing.northwind.example/api/packets', pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 15, pollingBatchLimit: 25, acknowledgementMode: 'SOURCE_PICKUP_ACK' },
    { ...base, connectionId: 'int-folder-branch', name: 'Branch scan share', description: 'Watched folder for branch scanners', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'WATCHED_FOLDER', authenticationType: 'NONE', fileShareRoot: '\\\\files.northwind.local\\lexavault\\inbox', secretConfigured: false, acknowledgementMode: 'NONE' },
    { ...base, connectionId: 'int-sdv-sharepoint', name: 'SharePoint · Underwriting', description: 'Primary redacted package destination', purpose: 'SDV_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'OAUTH2_CLIENT_CREDENTIALS', endpoint: 'https://northwind.sharepoint.example/sites/underwriting', timeoutSeconds: 45, acknowledgementMode: 'SDV_SUBMISSION_ACK' },
    { ...base, connectionId: 'int-derived-compliance', name: 'Compliance archive', description: 'Receives routed KYC pages', purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'FILE_SHARE', authenticationType: 'NONE', fileShareRoot: '\\\\archive.northwind.local\\compliance', secretConfigured: false, healthStatus: 'DEGRADED', lastFailureAt: iso(now - 2 * HOUR), lastFailureCategory: 'DESTINATION_TIMEOUT', acknowledgementMode: 'DURABLE_FILE_WRITE' },
    { ...base, connectionId: 'int-derived-income', name: 'Income verification queue', description: 'Routed income pages for verification', purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'JWT', endpoint: 'https://income.northwind.example/inbound', acknowledgementMode: 'DESTINATION_RECEIPT', enabled: false, healthStatus: 'UNKNOWN', lastSuccessAt: null },
  ];
}

function buildPollingRuns(now: number): PollingRun[] {
  return Array.from({ length: 8 }, (_, i) => {
    const started = now - (i * 15 + 4) * 60_000;
    const failed = i === 3;
    return {
      runId: `run-${i}`, connectionId: 'int-poll-servicing', trigger: i === 5 ? 'MANUAL' : 'SCHEDULED', status: failed ? 'FAILED' : i === 1 ? 'PARTIAL' : 'SUCCESS',
      startedAt: iso(started), completedAt: iso(started + 38_000), packagesDiscovered: failed ? 0 : (i * 3) % 5,
      documentsExpected: failed ? 0 : ((i * 3) % 5) * 3, documentsRetrieved: failed ? 0 : ((i * 3) % 5) * 3 - (i === 1 ? 1 : 0),
      packagesAcknowledged: failed ? 0 : (i * 3) % 5, failureCategory: failed ? 'SOURCE_UNAVAILABLE' : null, failureDetail: failed ? 'Upstream returned HTTP 503' : null,
    };
  });
}

function buildDeliveries(packages: DemoPackage[]): DerivedDelivery[] {
  const deliveries: DerivedDelivery[] = [];
  packages.filter((p) => p.status === 'COMPLETED').slice(0, 14).forEach((pkg, i) => {
    const doc = pkg.documents[0];
    deliveries.push({ derivedDeliveryId: `${pkg.packageId.slice(0, 8)}-r1`, packageId: pkg.packageId, sourceDocumentId: doc.documentId, exportCycle: 1, destinationLabel: 'Income verification', markerType: 'BARCODE', markerValue: 'UW-INCOME', pageNumbers: [2, 3], fileName: 'income-pages.pdf', status: 'DELIVERED', attemptCount: 1, retryable: false, destinationReference: `INC-${4410 + i}`, deliveredAt: pkg.lastExportedAt || undefined });
    if (i % 3 === 0) deliveries.push({ derivedDeliveryId: `${pkg.packageId.slice(0, 8)}-r2`, packageId: pkg.packageId, sourceDocumentId: doc.documentId, exportCycle: 1, destinationLabel: 'Compliance archive', markerType: 'QR_CODE', markerValue: 'COMP-KYC', pageNumbers: [5], fileName: 'kyc-pages.pdf', status: i % 2 ? 'RETRY_PENDING' : 'ATTENTION_REQUIRED', attemptCount: i % 2 ? 2 : 5, failureCategory: 'DESTINATION_TIMEOUT', retryable: true });
  });
  return deliveries;
}

function buildIncidents(packages: DemoPackage[], now: number): SupportIncidentDetail[] {
  return packages.filter((p) => p.status === 'FAILED' || p.status === 'REJECTED' || p.reprocessed).slice(0, 9).map((pkg, i) => {
    const failed = pkg.status === 'FAILED';
    const observed = new Date(pkg.createdAt).getTime() + 10 * 60_000;
    const resolved = !failed && pkg.status !== 'REJECTED';
    const reason = pkg.failureReason || 'DESTINATION_TIMEOUT';
    return {
      incidentId: `inc-${String(1040 + i)}`, tenantId: DEMO_TENANT_ID, packageId: pkg.packageId, documentId: pkg.documents[0]?.documentId ?? null,
      packageStatus: pkg.status, primaryIdentifierLabel: 'Contract Number', primaryIdentifierValue: pkg.identifiers.contract,
      status: resolved ? 'RESOLVED' : i % 3 === 0 ? 'RETRY_SCHEDULED' : 'OPEN', businessOutcome: resolved ? 'DELIVERED' : null,
      businessImpact: resolved ? 'COMPLETED' : failed ? 'BLOCKED' : 'CONTINUED', businessContinuityUsed: i % 4 === 1,
      stage: failed ? 'OCR' : pkg.status === 'REJECTED' ? 'INTAKE_VALIDATION' : 'DELIVERY', component: failed ? 'ocr-worker' : pkg.status === 'REJECTED' ? 'intake-validator' : 'sdv-delivery',
      failureCategory: failed ? 'DEPENDENCY_TIMEOUT' : pkg.status === 'REJECTED' ? 'INPUT_REJECTED' : 'DESTINATION_ERROR', reasonCode: reason, retryable: failed,
      attemptCount: failed ? 3 : 1, correlationId: demoId(),
      safeSummary: failed ? 'Text extraction exceeded the processing time limit on one document. The package is held and can be retried.' : pkg.status === 'REJECTED' ? 'The source PDF could not be opened. The submitter must provide a readable copy.' : 'Delivery to the destination timed out once and succeeded on retry.',
      artifactAvailability: failed ? 'RETAINED_UNTIL_POLICY_EXPIRY' : resolved ? 'PURGED' : 'PENDING_PURGE',
      technicalAttentionRetainedUntil: failed ? pkg.technicalHoldUntil : null, artifactsPurgedAt: resolved ? iso(now - DAY) : null,
      processingFingerprint: { pipelineVersion: '2026.10.2', ocrEngine: 'tesseract-5.4', detectorBundle: 'nw-2026.09' },
      safeMetadata: { pageCount: String(pkg.documents[0]?.pageCount ?? 0), sourceType: pkg.packageType },
      firstObservedAt: iso(observed), lastObservedAt: iso(observed + (failed ? 2 * HOUR : 20 * 60_000)), resolvedAt: resolved ? iso(observed + HOUR) : null,
      events: [
        { eventType: 'FAILURE_OBSERVED', attemptNumber: 1, scope: 'DOCUMENT', reasonCode: reason, retryable: failed, correlationId: demoId(), occurredAt: iso(observed), safeMetadata: {} as Record<string, string> },
        ...(failed ? [{ eventType: 'AUTOMATIC_RETRY', attemptNumber: 2, scope: 'DOCUMENT', reasonCode: reason, retryable: true, correlationId: demoId(), occurredAt: iso(observed + HOUR), safeMetadata: { backoffSeconds: '120' } }] : []),
        ...(resolved ? [{ eventType: 'RESOLVED', attemptNumber: 2, scope: 'PACKAGE', reasonCode: null, retryable: false, correlationId: demoId(), occurredAt: iso(observed + HOUR), safeMetadata: {} }] : []),
      ],
    };
  });
}

function buildBusinessAdmin(): BusinessAdminConfiguration {
  const validation = { validationType: 'TEXT' as const, exactLength: null, minLength: null, maxLength: 64, caseNormalization: 'PRESERVE' as const, stripSeparators: false, allowedValues: [], customPattern: null };
  return {
    identifiers: [
      { ...validation, id: 'idf-contract', key: 'contract', label: 'Contract Number', required: true, primary: true, searchable: true, extractable: true, displayable: true, displayOrder: 1, validationType: 'CUSTOM_PATTERN', customPattern: '^LN-\\d{4}-\\d{5}$', caseNormalization: 'UPPER' },
      { ...validation, id: 'idf-borrower', key: 'borrower', label: 'Borrower Last Name', required: true, primary: false, searchable: true, extractable: true, displayable: true, displayOrder: 2 },
      { ...validation, id: 'idf-branch', key: 'branch', label: 'Branch', required: false, primary: false, searchable: true, extractable: false, displayable: true, displayOrder: 3, validationType: 'ALLOW_LIST', allowedValues: BRANCHES },
    ],
    dashboardColumns: [
      { key: 'contract', label: 'Contract Number', kind: 'IDENTIFIER', primary: true, allowed: true, defaultVisible: true, displayOrder: 1 },
      { key: 'borrower', label: 'Borrower Last Name', kind: 'IDENTIFIER', primary: false, allowed: true, defaultVisible: true, displayOrder: 2 },
      { key: 'branch', label: 'Branch', kind: 'IDENTIFIER', primary: false, allowed: true, defaultVisible: false, displayOrder: 3 },
      { key: 'stage', label: 'Stage', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 4 },
      { key: 'source', label: 'Source', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 5 },
      { key: 'documents', label: 'Documents', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 6 },
      { key: 'exports', label: 'Exports', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: false, displayOrder: 7 },
      { key: 'received', label: 'Received', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 8 },
      { key: 'updated', label: 'Updated', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: true, displayOrder: 9 },
      { key: 'retention', label: 'Retention', kind: 'SYSTEM', primary: false, allowed: true, defaultVisible: false, displayOrder: 10 },
    ],
    sensitiveDataPolicy: { enabledEntityTypes: ENTITY_TYPES.slice(0, 7), autoRedactThreshold: 0.9, reviewMode: 'UNCERTAIN_ONLY', maskSsnLastFour: true },
    lifecyclePolicy: { originalRetentionHours: 720, redactedCacheHours: 336, neverExportedHoldHours: 168, finalApprovalRequired: true, maximumSuccessfulExportCycles: 3, maximumRedactedCacheHours: 720 },
    documentIntelligence: {
      mode: 'OBSERVE', activeBundleVersion: 'nw-2026.09', lastKnownGoodBundleVersion: 'nw-2026.06', configurationVersion: 7, baselineDiscoveryRequired: false,
      availableBundles: [
        { version: 'nw-2026.10-rc1', status: 'CANDIDATE', modelVersion: 'layout-4.2', promptVersion: 'p-18', detectorVersion: 'd-9.1', ocrRulesVersion: 'o-12', profileVersion: 'lending-v5', validationRulesVersion: 'v-22', thresholdsVersion: 't-8', description: 'Improved W-2 and paystub field detection' },
        { version: 'nw-2026.09', status: 'PROMOTED', modelVersion: 'layout-4.1', promptVersion: 'p-17', detectorVersion: 'd-9.0', ocrRulesVersion: 'o-12', profileVersion: 'lending-v4', validationRulesVersion: 'v-21', thresholdsVersion: 't-8', description: 'Current production bundle' },
        { version: 'nw-2026.06', status: 'PROMOTED', modelVersion: 'layout-4.0', promptVersion: 'p-15', detectorVersion: 'd-8.4', ocrRulesVersion: 'o-11', profileVersion: 'lending-v4', validationRulesVersion: 'v-19', thresholdsVersion: 't-7', description: 'Last known good' },
        { version: 'nw-2026.02', status: 'RETIRED', modelVersion: 'layout-3.8', promptVersion: 'p-12', detectorVersion: 'd-8.0', ocrRulesVersion: 'o-10', profileVersion: 'lending-v3', validationRulesVersion: 'v-15', thresholdsVersion: 't-6', description: null },
      ],
    },
    reportingConfiguration: {
      dimensions: [
        { definitionId: 'idf-branch', key: 'branch', label: 'Branch', enabled: true, displayOrder: 1 },
        { definitionId: 'idf-borrower', key: 'borrower', label: 'Borrower Last Name', enabled: false, displayOrder: 2 },
      ],
      deliveryTargetMinutes: 240, deliveryTargetPercent: 95, straightThroughTargetPercent: 70, reviewWaitTargetMinutes: 60,
    },
  };
}

function buildIdentity(now: number): EnterpriseIdentityOverview {
  const active = { connectionId: 'idp-entra-v4', tenantId: DEMO_TENANT_ID, providerId: 'entra', providerType: 'OIDC' as const, displayName: 'Northwind Entra ID', issuerUri: 'https://login.microsoftonline.example/northwind/v2.0', browserClientId: 'lexavault-web', apiAudience: 'api://lexavault', status: 'ACTIVE' as const, configurationVersion: 4, createdAt: iso(now - 60 * DAY), updatedAt: iso(now - 30 * DAY), lastTestedAt: iso(now - 30 * DAY), lastTestStatus: 'PASSED' as const, lastTestMessage: 'Discovery, JWKS and audience validated' };
  return { active, staged: null, rollbackCandidates: [{ ...active, connectionId: 'idp-entra-v3', configurationVersion: 3, status: 'DISABLED', updatedAt: iso(now - 90 * DAY) }], activationSupported: true, activationMaxTestAgeSeconds: 900 };
}

function buildStorage(now: number): ProcessingStorageOverview {
  return {
    ready: true,
    deploymentDefault: { id: 'asg-default', tenantId: null, providerId: 'psp-shared', scope: 'DEPLOYMENT_DEFAULT', activatedAt: iso(now - 40 * DAY) },
    tenantOverrides: [],
    providers: [
      { id: 'psp-shared', name: 'Shared NFS volume', providerType: 'SHARED_FILESYSTEM', rootPath: '/mnt/lexavault/processing', status: 'AVAILABLE', configurationVersion: 2 },
      { id: 'psp-local', name: 'Node-local scratch', providerType: 'LOCAL_FILESYSTEM', rootPath: '/var/lib/lexavault/scratch', status: 'CANDIDATE', configurationVersion: 1 },
    ],
    nodeValidations: [
      { nodeId: 'lv-worker-01', processInstanceId: 'p-81a2', providerId: 'psp-shared', status: 'HEALTHY', failureCode: null, checkedAt: iso(now - 5 * 60_000) },
      { nodeId: 'lv-worker-02', processInstanceId: 'p-27c9', providerId: 'psp-shared', status: 'HEALTHY', failureCode: null, checkedAt: iso(now - 6 * 60_000) },
    ],
    tenants: [
      { tenantId: DEMO_TENANT_ID, name: DEMO_TENANT_NAME, slug: DEMO_TENANT_SLUG, status: 'ACTIVE' },
      { tenantId: '00000000-0000-0000-0000-000000000002', name: 'Cascade Credit Union', slug: 'cascade', status: 'ACTIVE' },
    ],
    activeNodeCount: 2,
  };
}

function buildLicensing(now: number) {
  const claims = (tenantId: string, customerName: string, edition: string, expiresInDays: number) => ({
    schemaVersion: 2, licenseId: `lic-${tenantId.slice(-4)}-2026`, tenantId, customerName, edition, issuedAt: iso(now - 200 * DAY), notBefore: iso(now - 200 * DAY), expiresAt: iso(now + expiresInDays * DAY),
    entitlements: ['DOCUMENT_INTAKE', 'MANUAL_REVIEW', 'DERIVED_ROUTING', 'REPORTING'], limits: { monthlyPages: 250000, tenantUsers: 150 },
  });
  const licensing: LicensingOverview = {
    enforcementEnabled: true, verificationKeyConfigured: true,
    tenants: [
      { tenantId: DEMO_TENANT_ID, tenantName: DEMO_TENANT_NAME, tenantSlug: DEMO_TENANT_SLUG, status: 'VALID', processingAllowed: true, licenseRecordId: 'rec-1', claims: claims(DEMO_TENANT_ID, DEMO_TENANT_NAME, 'ENTERPRISE', 165), reasonCode: 'LICENSE_VALID' },
      { tenantId: '00000000-0000-0000-0000-000000000002', tenantName: 'Cascade Credit Union', tenantSlug: 'cascade', status: 'EXPIRING_SOON', processingAllowed: true, licenseRecordId: 'rec-2', claims: claims('00000000-0000-0000-0000-000000000002', 'Cascade Credit Union', 'STANDARD', 12), reasonCode: 'LICENSE_EXPIRING' },
    ],
  };
  const history: Record<string, LicenseHistoryItem[]> = {};
  licensing.tenants.forEach((t) => {
    history[t.tenantId] = [
      { recordId: t.licenseRecordId || 'rec', licenseId: t.claims?.licenseId || 'lic', installedAt: iso(now - 200 * DAY), installedBy: 'platform.ops@lexavault.example', active: true, status: t.status, claims: t.claims, reasonCode: t.reasonCode, verificationEvidence: 'CURRENT_KEY' },
      { recordId: `${t.licenseRecordId}-old`, licenseId: `${t.claims?.licenseId}-prev`, installedAt: iso(now - 565 * DAY), installedBy: 'platform.ops@lexavault.example', active: false, status: 'SUPERSEDED', claims: null, reasonCode: 'LICENSE_SUPERSEDED', verificationEvidence: 'VERIFIED_AT_INSTALL' },
    ];
  });
  return { licensing, history };
}

export function createSeedState(options: { empty?: boolean } = {}): DemoState {
  const now = Date.now();
  const rand = seededRandom(20261025);
  const { packages, findings } = options.empty ? { packages: [], findings: [] } : buildPackages(now, rand);
  const { licensing, history } = buildLicensing(now);
  return {
    version: STATE_VERSION,
    seededAt: iso(now),
    packages,
    findings,
    audit: options.empty ? [] : buildAudit(packages),
    integrations: buildIntegrations(now),
    pollingRuns: buildPollingRuns(now),
    routingRules: [
      { ruleId: 'rr-income', destinationLabel: 'Income verification', markerType: 'BARCODE', markerValue: 'UW-INCOME', scanRegion: 'TOP_RIGHT', destinationConnectionId: 'int-derived-income', enabled: true },
      { ruleId: 'rr-kyc', destinationLabel: 'Compliance archive', markerType: 'QR_CODE', markerValue: 'COMP-KYC', scanRegion: 'BOTH', destinationConnectionId: 'int-derived-compliance', enabled: true },
      { ruleId: 'rr-title', destinationLabel: 'Title desk', markerType: 'TEXT_PATTERN', markerValue: 'TITLE-\\d{6}', scanRegion: 'BOTTOM_LEFT', destinationConnectionId: 'int-derived-compliance', enabled: false },
    ],
    deliveries: options.empty ? [] : buildDeliveries(packages),
    incidents: options.empty ? [] : buildIncidents(packages, now),
    businessAdmin: buildBusinessAdmin(),
    brandingDeployment: { id: 'brand-deploy', tenantId: null, applicationName: 'LexaVault', shortName: 'LexaVault', organizationName: null, pageTitle: 'LexaVault', accentColor: null, secondaryColor: null, primaryLogoDataUrl: null, compactLogoDataUrl: null, faviconDataUrl: null, updatedBy: 'platform.ops@lexavault.example', updatedAt: iso(now - 100 * DAY) },
    tenantBranding: {},
    identity: buildIdentity(now),
    storage: buildStorage(now),
    licensing,
    licenseHistory: history,
    scannerSession: null,
    scannerDeliveries: options.empty ? [] : [
      { sessionId: 'scan-s1', submissionId: 'sub-81', sourceDocumentId: 'src-81', status: 'SUBMITTED', captureMode: 'SIMULATOR', bridgeId: 'bridge-sea-01', siteId: 'seattle', scannerId: 'fi-7160', lexavaultPackageId: packages[3]?.packageId ?? null, sizeBytes: 1_480_220, deliveryAttemptCount: 1, queuedAt: iso(now - 2 * HOUR), submittedAt: iso(now - 2 * HOUR + 4000) },
      { sessionId: 'scan-s2', submissionId: 'sub-82', sourceDocumentId: 'src-82', status: 'ATTENTION_REQUIRED', captureMode: 'SIMULATOR', bridgeId: 'bridge-sea-01', siteId: 'seattle', scannerId: 'fi-7160', lexavaultPackageId: null, sizeBytes: 902_114, deliveryAttemptCount: 4, queuedAt: iso(now - 50 * 60_000), nextDeliveryAttemptAt: null, lastDeliveryErrorCode: 'LEXAVAULT_UNAVAILABLE', lastDeliveryErrorAt: iso(now - 10 * 60_000) },
    ],
  };
}

let current: DemoState | null = null;

export function loadState(): DemoState {
  if (current) return current;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as DemoState;
      if (parsed.version === STATE_VERSION) {
        current = parsed;
        return current;
      }
    }
  } catch {
    // Corrupt demo state falls through to a fresh seed.
  }
  current = createSeedState();
  saveState();
  return current;
}

export function saveState() {
  if (!current) return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
  } catch {
    // Storage quota exceeded: demo continues in memory.
  }
}

export function resetState(options: { empty?: boolean } = {}) {
  current = createSeedState(options);
  saveState();
}
