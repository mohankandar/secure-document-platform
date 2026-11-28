/** Task 0061: platform-fields' types. "Numbers only" is NUMBER with a pattern, "Letters and numbers" TEXT with one. */
export type BusinessIdentifierValidationType = 'TEXT' | 'NUMBER' | 'DECIMAL' | 'MONEY' | 'DATE' | 'DATE_TIME' | 'EMAIL' | 'UUID' | 'DROPDOWN' | 'ADDRESS' | 'CUSTOM_PATTERN';
export type CaseFieldSearchMode = 'EXACT' | 'PREFIX' | 'PARTIAL';
export type BusinessIdentifierCaseNormalization = 'PRESERVE' | 'UPPER' | 'LOWER';

export interface BusinessIdentifierValidationConfiguration {
  validationType: BusinessIdentifierValidationType;
  exactLength?: number | null;
  minLength?: number | null;
  maxLength?: number | null;
  caseNormalization: BusinessIdentifierCaseNormalization;
  stripSeparators: boolean;
  allowedValues: string[];
  customPattern?: string | null;
  /** How Cases finds a value; a sensitive field only exactly. */
  searchMode?: CaseFieldSearchMode;
  /** Kept encrypted; shown only to the roles that see the documents. */
  sensitive?: boolean;
  multiValue?: boolean;
  currency?: string | null;
  minValue?: string | null;
  maxValue?: string | null;
}

export interface BusinessIdentifier {
  key: string;
  label: string;
  value: string;
  primary: boolean;
  searchable: boolean;
  displayable: boolean;
  displayOrder: number;
  /** Task 0061: the value is hidden unless the role may see it. */
  sensitive?: boolean;
}

export interface IdentifierDefinition extends BusinessIdentifierValidationConfiguration {
  key: string;
  label: string;
  required: boolean;
  primary: boolean;
  searchable: boolean;
  extractable: boolean;
  displayable: boolean;
  displayOrder: number;
}


export interface BusinessAdminIdentifierDefinition extends IdentifierDefinition {
  id: string;
  /** Task 0061: values exist, so type, sensitive, main ID and several-values stay. */
  locked?: boolean;
}

export type DocumentIntelligenceMode = 'DISABLED' | 'OBSERVE' | 'ENABLED';

export interface IntelligenceBundleConfiguration {
  version: string;
  status: 'CANDIDATE' | 'PROMOTED' | 'RETIRED' | string;
  modelVersion: string;
  promptVersion: string;
  detectorVersion: string;
  ocrRulesVersion: string;
  profileVersion: string;
  validationRulesVersion: string;
  thresholdsVersion: string;
  description?: string | null;
  /** When the version was released (promoted). */
  promotedAt?: string | null;
}

export interface DocumentIntelligenceConfiguration {
  mode: DocumentIntelligenceMode;
  activeBundleVersion: string;
  lastKnownGoodBundleVersion: string;
  configurationVersion: number;
  baselineDiscoveryRequired: boolean;
  availableBundles: IntelligenceBundleConfiguration[];
}

export type BusinessReviewMode = 'UNCERTAIN_ONLY' | 'REVIEW_ALL';

export interface SensitiveDataPolicyConfiguration {
  enabledEntityTypes: string[];
  autoRedactThreshold: number;
  reviewMode: BusinessReviewMode;
  maskSsnLastFour: boolean;
  /** Task 0044: invisible OCR text on redacted pages; on by default. Not sent = the server keeps its value. */
  searchableRedactedPages?: boolean;
  /** Task 0064. Absent from an older server: on, 0.85. */
  visionEnabled?: boolean;
  visionOcrThreshold?: number;
  /** Task 0083: a second AI reads the finished file. Absent from an older server: off. */
  secondCheckEnabled?: boolean;
  /** Task 0065: a document's people are looked for on every page. Absent from an older server: off. */
  documentContextEnabled?: boolean;
  /** Task 0119: the final check reads picture pages no person looked at again (OCR and the vision model). Absent from an older server: on. */
  finalCheckRereadEnabled?: boolean;
  /** Task 0062: regulation packs in force, and the kinds whose last four digits stay visible. */
  enabledPacks?: string[];
  keepLastFourKinds?: string[];
  /** Task 0062, read only: false for an entity from before the catalog, until its policy is saved. */
  lockedItemsOn?: boolean;
}

/** Task 0062: the redaction catalog and the regulation packs, the same for every entity. */
export interface RedactionCatalog {
  kinds: { key: string; name: string; detectionMethod: string; defaultStyle: string; locked: boolean; lastFourAllowed: boolean }[];
  packs: { key: string; name: string; covers: string; notCovered: string; kinds: string[]; suggestedKinds?: string[]; secondCheck?: boolean; suggestsDocumentContext?: boolean }[];
}

export interface LifecyclePolicyConfiguration {
  originalRetentionHours: number;
  redactedCacheHours: number;
  neverExportedHoldHours: number;
  finalApprovalRequired: boolean;
  maximumSuccessfulExportCycles: number;
  maximumRedactedCacheHours: number;
}

export interface DashboardAdminColumnConfiguration {
  key: string;
  label: string;
  kind: 'IDENTIFIER' | 'SYSTEM' | string;
  primary: boolean;
  allowed: boolean;
  defaultVisible: boolean;
  displayOrder: number;
}


export interface ReportingDimensionConfiguration {
  definitionId: string;
  key: string;
  label: string;
  enabled: boolean;
  displayOrder: number;
}

export interface ReportingConfiguration {
  dimensions: ReportingDimensionConfiguration[];
  deliveryTargetMinutes: number;
  deliveryTargetPercent: number;
  straightThroughTargetPercent: number;
  reviewWaitTargetMinutes: number;
  /** 0–100: pick up reviews within reviewWaitTargetMinutes for at least this share of reviews. */
  reviewWaitTargetPercent: number;
  /** Task 0020: minutes a person would take to check one page by hand (0.5–30, steps of 0.5). U024 edits it. */
  minutesPerPage?: number;
}

export interface BusinessAdminConfiguration {
  identifiers: BusinessAdminIdentifierDefinition[];
  dashboardColumns: DashboardAdminColumnConfiguration[];
  sensitiveDataPolicy: SensitiveDataPolicyConfiguration;
  lifecyclePolicy: LifecyclePolicyConfiguration;
  documentIntelligence: DocumentIntelligenceConfiguration;
  reportingConfiguration: ReportingConfiguration;
  redactionCatalog?: RedactionCatalog;
  /** Task 0061: fields switched off; never deleted. */
  switchedOffIdentifiers?: BusinessAdminIdentifierDefinition[];
  /** Task 0061: a key service is set up, so a field can be sensitive. */
  sensitiveFieldsAvailable?: boolean;
}

export interface BusinessAdminIdentifierDraft extends BusinessIdentifierValidationConfiguration {
  id?: string;
  locked?: boolean;
  label: string;
  required: boolean;
  primary: boolean;
  searchable: boolean;
  extractable: boolean;
  displayable: boolean;
}


export type RoutingMarkerType = 'BARCODE' | 'QR_CODE' | 'TEXT_PATTERN';
export type RoutingScanRegion = 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTH';

export interface RoutingDestinationOption {
  connectionId: string;
  name: string;
  providerType: 'HTTP_API' | 'FILE_SHARE' | string;
  enabled: boolean;
  healthStatus: string;
}

export interface RoutingRule {
  ruleId: string;
  destinationLabel: string;
  markerType: RoutingMarkerType;
  markerValue: string;
  scanRegion: RoutingScanRegion;
  destinationConnectionId: string;
  enabled: boolean;
}

export interface RoutingRuleRequest {
  destinationLabel: string;
  markerType: RoutingMarkerType;
  markerValue: string;
  scanRegion: RoutingScanRegion;
  destinationConnectionId: string;
  enabled: boolean;
}

export interface DerivedDelivery {
  derivedDeliveryId: string;
  packageId: string;
  sourceDocumentId: string;
  exportCycle: number;
  destinationLabel?: string;
  markerType?: RoutingMarkerType;
  markerValue?: string;
  pageNumbers: number[];
  status: 'PENDING' | 'DELIVERING' | 'RETRY_PENDING' | 'DELIVERED' | 'ATTENTION_REQUIRED' | string;
  attemptCount: number;
  failureCategory?: string;
  /** Task 0025: the LV code for failureCategory, when the server sends it. */
  lvCode?: string | null;
  retryable: boolean;
  destinationReference?: string;
  deliveredAt?: string;
  /** Only in the attention list: the case's main ID (for example the loan number), when the case has one. */
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
}

export interface PackageSummary {
  packageId: string;
  tenantId?: string;
  packageType: string;
  status: string;
  businessIdentifiers: BusinessIdentifier[];
  documentCount: number;
  createdAt: string;
  updatedAt: string;
  /** Task 0029: the case's export state (GET /packages); absent from an older server and on the detail (= NONE). */
  exportState?: string;
  /** Task 0073: URGENT, NORMAL or LOW. */
  priority?: string;
  /** Task 0038: why the case stopped or waits, as an LV code (GET /packages); null or absent when it doesn't. */
  stopReasonCode?: string | null;
  /** Task 0038: the delivery destination's name once one is chosen (GET /packages). */
  destinationName?: string | null;
}

export interface PackageDetail extends PackageSummary {
  /** Task 0021: the case level as sent by the source system, and the case's effective level (read-only). */
  sensitivityLevel?: string;
  effectiveSensitivityLevel?: string;
  /** Task 0050: a digitally signed document of this case lost its signature by redaction. */
  signatureRemoved?: boolean;
  /** Task 0073: URGENT, NORMAL or LOW. */
  priority?: string;
  /** Task 0071: why a person rejected the case (LV code). */
  rejectionCode?: string | null;
  metadata?: Record<string, string>;
  documents: DocumentSummary[];
}

export interface DocumentSummary {
  documentId: string;
  fileName?: string;
  mimeType: string;
  sizeBytes: number;
  documentType?: string;
  status: string;
  /** Task 0021: the document's own level as sent, and the higher of it and the case level. */
  sensitivityLevel?: string;
  effectiveSensitivityLevel?: string;
}

export interface CreatePackageRequest {
  /** Task 0065: the New case "Instructions for the AI"; only where the entity switched them on. */
  caseInstructions?: string;
  /** Task 0073: the New case "Priority" choice. */
  priority?: string;
  packageType: string;
  businessIdentifiers: Record<string, string>;
  metadata?: Record<string, string>;
  /** Task 0021: the New case "Sensitivity" choice (the case level). */
  sensitivityLevel?: string;
}

export interface PackageStatusResult {
  packageId: string;
  status: string;
}

export interface DocumentUploadResult {
  documentId: string;
  artifactId: string;
  status: string;
}

export interface ReviewTaskResult {
  reviewTaskId: string;
  tenantId?: string;
  packageId: string;
  status: string;
  assignedTo?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AccessRequestResult {
  id: string;
  tenantId: string;
  vaultEntryId: string;
  artifactId: string;
  requestedBy: string;
  status: string;
  requestedAt: string;
  expiresAt: string;
  decidedAt?: string;
}

export interface VaultEntryResult {
  vaultEntryId: string;
  packageId: string;
  documentId: string;
  artifactId: string;
  artifactType: string;
  registeredAt: string;
}

export interface ApiError {
  errorCode?: string;
  /** Task 0025: the LexaVault error code, for example "LV-CFG-001" (absent from an older server). */
  code?: string;
  message?: string;
  correlationId?: string;
}

export interface AuditRecordResult {
  id: string;
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  outcome: string;
  correlationId: string;
  detail?: string;
  occurredAt: string;
  /** Task 0016: recorded at the time of the action; null for system work and older records. */
  actorDisplayName?: string | null;
  caseId?: string | null;
}

export interface ReviewBoundingBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface FocusedReviewItem {
  findingId: string;
  packageId: string;
  documentId: string;
  fileName: string;
  entityType: string;
  confidence: number;
  pageNumber: number;
  hasBoundingBox: boolean;
  boundingBox?: ReviewBoundingBox | null;
  reason: 'LOW_CONFIDENCE' | 'LOCATION_UNCERTAIN' | 'POLICY_REVIEW' | string;
  /** The text found and the same line around it. Sensitive: show in full only in the decision panel, masked elsewhere; never log or store it. Absent when it cannot be read safely. */
  foundText?: ReviewFoundText | null;
  /** Task 0064: TEXT, SIGNATURE, VISION, PAGE_CHECK. Empty for findings saved before. */
  foundBy?: string[] | null;
  /** Task 0083: the kinds the second check of the finished file found on this page; absent for every other item. */
  secondCheck?: string[] | null;
}


export interface ReviewFoundText {
  text: string;
  before: string;
  after: string;
}

export interface FocusedReviewQueue {
  items: FocusedReviewItem[];
  totalItems: number;
  /** The policy's automatic-redaction level (0..1), for "why a person is asked". */
  autoRedactThreshold?: number | null;
}

export interface ReviewPagePreview {
  blob: Blob;
  pageNumber: number;
}

export interface ReviewResolutionResult {
  findingId: string;
  packageId: string;
  status: string;
  processingResumed: boolean;
}


export interface ManualFallbackDocument {
  documentId: string;
  fileName: string;
  pageCount: number;
}

export interface ManualFallbackRegion extends ReviewBoundingBox {
  findingId: string;
  documentId: string;
  pageNumber: number;
  entityType: string;
}

export interface ManualFallbackDetail {
  packageId: string;
  status: string;
  documents: ManualFallbackDocument[];
  regions: ManualFallbackRegion[];
  enabledEntityTypes: string[];
}

export interface ManualFallbackPagePreview {
  blob: Blob;
  pageNumber: number;
}

export interface PackageLifecycleSummary {
  packageId: string;
  successfulExportCycles: number;
  maximumExportCycles: number;
  canReprocess: boolean;
  finalApprovalRequired: boolean;
  finalApprovalGranted: boolean;
  lastExportedAt?: string | null;
  originalDeleteAt?: string | null;
  redactedDeleteAt?: string | null;
  technicalHoldUntil?: string | null;
  redactedPurgedAt?: string | null;
  originalPurgedAt?: string | null;
  purgedAt?: string | null;
  lastDestinationType?: string | null;
}

export type DashboardStage =
  | 'ALL'
  | 'PROCESSING'
  | 'NEEDS_REVIEW'
  | 'NEEDS_APPROVAL'
  | 'EXPORTED'
  | 'TECHNICAL_ATTENTION'
  | 'REJECTED';

export interface DashboardColumnDefinition {
  key: string;
  label: string;
  kind: 'IDENTIFIER' | 'SYSTEM' | string;
  primary: boolean;
  searchable: boolean;
  defaultVisible: boolean;
  displayOrder: number;
}

export interface DashboardIdentifierValue {
  key: string;
  label: string;
  value: string;
  primary: boolean;
  displayOrder: number;
}

export interface DashboardOverview {
  total: number;
  processing: number;
  needsReview: number;
  needsApproval: number;
  exported: number;
  technicalAttention: number;
  rejected: number;
}

export interface DashboardItem {
  packageId: string;
  packageType: string;
  status: string;
  stage: DashboardStage;
  attention: 'NONE' | 'REVIEW' | 'APPROVAL' | 'TECHNICAL' | 'REJECTED' | string;
  identifiers: DashboardIdentifierValue[];
  documentCount: number;
  successfulExportCycles: number;
  lastExportedAt?: string | null;
  originalDeleteAt?: string | null;
  technicalHoldUntil?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OperationsDashboard {
  overview: DashboardOverview;
  columns: DashboardColumnDefinition[];
  items: DashboardItem[];
  totalItems: number;
  offset: number;
  limit: number;
}

export interface OperationalReports {
  generatedAt: string;
  period: {
    from: string;
    to: string;
    previousFrom: string;
    previousTo: string;
    source: string;
    outcome: string;
  };
  executive: {
    batches: number;
    previousBatches: number;
    documents: number;
    pages: number;
    completed: number;
    previousCompleted: number;
    delivered: number;
    previousDelivered: number;
    periodIntakeDelivered: number;
    previousPeriodIntakeDelivered: number;
    completionRate: number;
    deliveryRate: number;
    periodIntakeDeliveryRate: number;
    medianDeliveryHours: number;
    p95DeliveryHours: number;
    straightThroughRate: number;
    reviewRate: number;
    currentBacklog: number;
    currentAttention: number;
    oldestBacklogHours: number;
    trend: Array<{ bucket: string; received: number; delivered: number }>;
  };
  intake: {
    totalBatches: number;
    manual: number;
    api: number;
    watchedFolder: number;
    scanner: number;
    waitingForTrigger: number;
    duplicateObservations: number;
    rejectedObservations: number;
  };
  processing: {
    processing: number;
    needsReview: number;
    needsApproval: number;
    completed: number;
    technicalAttention: number;
    rejected: number;
  };
  export: {
    exportedPackages: number;
    successfulExportCycles: number;
    pendingFinalApproval: number;
  };
  retention: {
    originalsDueWithin24Hours: number;
    redactedCopiesDueWithin24Hours: number;
    technicalHoldsDueWithin24Hours: number;
    purgedPackages: number;
    originalsOverdue: number;
    redactedCopiesOverdue: number;
  };
  timing: {
    medianTurnaroundHours: number;
    p95TurnaroundHours: number;
    medianActiveProcessingHours: number;
    p95ActiveProcessingHours: number;
    medianReviewWaitHours: number;
    medianReviewHandlingHours: number;
    turnaroundSamples: number;
    activeProcessingSamples: number;
    reviewWaitSamples: number;
    reviewHandlingSamples: number;
  };
  backlog: {
    underOneHour: number;
    oneToFourHours: number;
    fourToTwentyFourHours: number;
    oneToThreeDays: number;
    overThreeDays: number;
    oldestHours: number;
  };
  usage: {
    packages: number;
    documents: number;
    pages: number;
    averageDocumentsPerPackage: number;
    averagePagesPerPackage: number;
    reviewedPackages: number;
    straightThroughPackages: number;
    reprocessedPackages: number;
  };
  serviceLevel: {
    deliveryTargetMinutes: number;
    deliveryTargetPercent: number;
    deliveryWithinTargetRate: number;
    deliveryEligible: number;
    deliveryBreaches: number;
    straightThroughTargetPercent: number;
    straightThroughActualPercent: number;
    reviewWaitTargetMinutes: number;
    reviewWaitTargetPercent: number;
    reviewWithinTargetRate: number;
    reviewEligible: number;
    reviewBreaches: number;
  };
  dimensions: Array<{
    key: string;
    label: string;
    values: Array<{ value: string; packages: number; delivered: number; deliveryRate: number; medianTurnaroundHours: number }>;
  }>;
  /** Task 0020: "What LexaVault did for you" (U024 shows it). percentNoPerson is null with no delivered cases. */
  timeSaved?: {
    casesDelivered: number;
    casesNoPerson: number;
    percentNoPerson: number | null;
    pagesChecked: number;
    documentsChecked: number;
    itemsRedacted: number;
    itemTypes: string[];
    pagesNoPerson: number;
    minutesPerPage: number | null;
    minutesSaved: number;
  };

}


export type IntegrationPurpose = 'INGESTION_SOURCE' | 'DOCUMENT_STORE_DELIVERY' | 'DERIVED_DOCUMENT_DELIVERY';
export type IntegrationDirection = 'INBOUND' | 'OUTBOUND';
export type IntegrationProviderType = 'PUSH_API' | 'HTTP_API' | 'FILE_SHARE' | 'WATCHED_FOLDER' | 'SCANNER';
export type IntegrationAuthenticationType = 'NONE' | 'BASIC' | 'API_KEY' | 'JWT' | 'OAUTH2_CLIENT_CREDENTIALS' | 'MTLS';
export type IntegrationAcknowledgementMode = 'NONE' | 'HTTP_SUCCESS' | 'DESTINATION_RECEIPT' | 'DURABLE_FILE_WRITE' | 'SOURCE_PICKUP_ACK' | 'SUBMISSION_ACK';
export type IntegrationPollingScheduleType = 'INTERVAL' | 'DAILY';

export interface IntegrationConnection {
  connectionId: string;
  name: string;
  description?: string | null;
  purpose: IntegrationPurpose;
  direction: IntegrationDirection;
  providerType: IntegrationProviderType;
  authenticationType: IntegrationAuthenticationType;
  clientPrincipal?: string | null;
  endpoint?: string | null;
  /** Task 0110: host names allowed to be internal or plain http. Absent from an older server: none. */
  allowedHosts?: string[];
  fileShareRoot?: string | null;
  secretConfigured: boolean;
  timeoutSeconds: number;
  retryMaxAttempts: number;
  incompleteWindowHours: number;
  maxDocumentsPerIntake: number;
  maxPdfSizeBytes: number;
  maxPdfPages: number;
  pollingScheduleType?: IntegrationPollingScheduleType | null;
  pollingIntervalMinutes?: number | null;
  pollingLocalTime?: string | null;
  pollingTimeZone?: string | null;
  pollingBatchLimit?: number | null;
  acknowledgementMode: IntegrationAcknowledgementMode;
  enabled: boolean;
  configurationVersion: number;
  healthStatus: string;
  lastSuccessAt?: string | null;
  lastFailureAt?: string | null;
  lastFailureCategory?: string | null;
  createdAt: string;
  updatedAt: string;
  /** Task 0087: a document store connection's profile and settings; null when IT has not chosen a profile yet. */
  destination?: DestinationSettings | null;
}

/** Task 0087: what IT adds to a destination's published profile. No secret. */
export interface DestinationSettings {
  profileFile: string;
  profileName?: string | null;
  profileVersion?: string | null;
  tokenAddress?: string | null;
  clientId?: string | null;
  scope?: string | null;
  audience?: string | null;
  clientSecretBasic: boolean;
  sendAllFields: boolean;
  fieldMapping: Record<string, string>;
  sensitiveFields: string[];
  caseLevel: 'REFUSE' | 'ON_EACH_DOCUMENT';
  levelRefusalCodes: string[];
  settings: Record<string, string>;
}

/** Task 0087: one profile file of the installation's profile folder. */
export interface DestinationProfile {
  file: string;
  name: string | null;
  version: string | null;
  description: string | null;
  signInTypes: string[];
  fields: string[];
  takesFields: boolean;
  otherFields: boolean;
  carriesLevel: boolean;
  carriesCaseLevel: boolean;
  notes: string[];
  problems: string[];
}

export interface IntegrationConnectionRequest {
  destination?: DestinationSettings;
  name: string;
  description?: string;
  purpose: IntegrationPurpose;
  direction: IntegrationDirection;
  providerType: IntegrationProviderType;
  authenticationType: IntegrationAuthenticationType;
  clientPrincipal?: string;
  endpoint?: string;
  /** Task 0110: host names allowed to be internal or plain http; absent keeps the stored ones. */
  allowedHosts?: string[];
  fileShareRoot?: string;
  secretReference?: string;
  clearSecretReference?: boolean;
  timeoutSeconds?: number;
  retryMaxAttempts?: number;
  incompleteWindowHours?: number;
  maxDocumentsPerIntake?: number;
  maxPdfSizeBytes?: number;
  maxPdfPages?: number;
  pollingScheduleType?: IntegrationPollingScheduleType;
  pollingIntervalMinutes?: number;
  pollingLocalTime?: string;
  pollingTimeZone?: string;
  pollingBatchLimit?: number;
  acknowledgementMode: IntegrationAcknowledgementMode;
  enabled?: boolean;
}


/** Answer of a test before saving: only whether it works and, if not, a fixed reason code. */
export interface DraftConnectionTestResult {
  works: boolean;
  reasonCode: string | null;
  /** Task 0025: the LV code for reasonCode, when the server sends it. */
  lvCode?: string | null;
}

export interface DocumentStoreConnectionTestResult {
  connectionId: string;
  healthStatus: string;
  secretConfigured: boolean;
  configurationVersion: number;
}

export interface PollingRun {
  runId: string; connectionId: string; trigger: 'SCHEDULED' | 'MANUAL'; status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  startedAt: string; completedAt?: string | null; packagesDiscovered: number; documentsExpected: number;
  documentsRetrieved: number; packagesAcknowledged: number; failureCategory?: string | null; failureDetail?: string | null;
  /** Task 0025: the LV code for failureCategory, when the server sends it. */
  lvCode?: string | null;
}

export interface PollingConnectionStatus {
  connectionId: string; name: string; enabled: boolean; healthStatus: string; nextScheduledAt?: string | null; recentRuns: PollingRun[];
}

export interface PlatformHealthItem {
  key: string;
  label: string;
  category: string;
  provider: string;
  status: string;
  configured: boolean;
  detail: string;
  /** Task 0026: LV-SYS-006 when the check failed; absent or null otherwise. */
  lvCode?: string | null;
}

export interface TechnicalExceptionItem {
  packageId: string;
  reasonCode: string;
  /** Task 0025: the LV code for reasonCode, when the server sends it. */
  lvCode?: string | null;
  status: string;
  documentCount: number;
  failedAt: string;
  technicalHoldUntil?: string | null;
}

/** Task 0021: the watched folder's settings (Tenant IT). */
export interface WatchedFolderSettings {
  folderKey: string;
  /** Task 0023 A4: null or missing when nothing is saved; the screen shows Standard. */
  sensitivityLevel?: string | null;
}

export interface WatchedFolderAdminStatus {
  enabled: boolean;
  schedulingEnabled: boolean;
  scanIntervalMs: number;
  rootPath: string;
  triggerFileName: string;
  incompleteVisibilityMinutes: number;
  acceptedBehavior: string;
  rejectedBehavior: string;
  processingDirectory: string;
  coordinationEnabled: boolean;
  nodeId?: string | null;
  nodeInstanceId?: string | null;
  scannerRole?: string | null;
  leaseOwner?: string | null;
  fencingToken?: number | null;
  leaseAcquiredAt?: string | null;
  leaseHeartbeatAt?: string | null;
  leaseExpiresAt?: string | null;
  takeoverCount?: number;
  lastScanAt?: string | null;
  lastScanOutcome: string;
  lastScanResult?: {
    discovered: number;
    waitingForTrigger: number;
    accepted: number;
    rejected: number;
    duplicates: number;
  } | null;
}

/** Task 0081: the filters all Reports tabs share. */
export interface ReportFilters { from: string; to: string; sourceSystem: string; level: string }
export interface ReportSource { key: string; name: string }
interface UsualRange { low: number; high: number }

/** Reports › Daily intake and delivery. Counts, LV codes and connection names only. */
/** Task 0105: Reports › Automation. */
export type AutomationPeriod = 'TODAY' | 'WEEK' | 'MONTH';

export interface AutomationReport {
  period: AutomationPeriod;
  from: string;
  to: string;
  /** Whole percent; null when nothing was redacted in the period. */
  automaticPercent: number | null;
  automatic: number;
  placedByPerson: number;
  drawnByPerson: number;
  byText: number;
  byVision: number;
}

export interface DailyReport {
  generatedAt: string;
  from: string;
  to: string;
  includesNow: boolean;
  /** False with a level filter: a refusal has no case, so no level. */
  refusalsCounted: boolean;
  quiet: { sourceKey: string; name: string; lastReceivedAt?: string | null; usualMinutes: number }[];
  received: { cases: number; documents: number; pages: number; urgent: number; usual?: UsualRange | null };
  exported: { cases: number; documents: number; pages: number; splitDocuments: number; urgent: number; usual?: UsualRange | null };
  notExported: { cases: number; ready: number; waitingToRetry: number; destinationPaused: number; waitingForYou: number; urgent: number; oldestWaitingSeconds?: number | null };
  attention: { refused: number; stopped: number; exportFailed: number; rejected: number };
  reasons: { code: string; where: string; cases: number }[];
  destinations: { key: string; name: string; split: boolean; paused: boolean; exported: number; documents: number; pages: number; failed: number; waitingToRetry: number }[];
  sources: { key: string; name: string; lastReceivedAt?: string | null; received: number; duplicates: number; refused: number; exported: number; inProgress: number; stopped: number; quiet: boolean }[];
  handled: { noPerson: number; afterReview: number; byHand: number; inProgress: number; stopped: number; rejected: number };
  levels: { level: string; received: number; exported: number }[];
  waitingForTrigger: number;
}

/** Task 0078: the queue of each stage (one entity's, or every entity's added up) and its ETA. */
export interface PipelineQueue {
  staged: boolean;
  allEntities: boolean;
  windowMinutes: number;
  etaSeconds?: number | null;
  stages: { stage: string; waiting: number; running: number; held: number; toCome: number; workers: number; highest: number;
    oldestWaitingSeconds?: number | null; perMinute?: number | null; etaSeconds?: number | null }[];
}

/** Task 0067: the workers of the server that answered, and IT's own maximums. */
/** Task 0079: a migration source; the times are the entity's local times. */
export interface BulkSource {
  source: string; from: string; to: string; weekends: boolean; open: boolean;
  done: number; waiting: number; failed: number; perOpenHour?: number | null; expectedFinish?: string | null;
}
export interface BulkSources { sources: BulkSource[] }

export interface SourcePriorities { sources: Record<string, string> }

export interface ProcessingCapacity {
  staged: boolean;
  stages: { stage: string; current: number; busy: number; lowest: number; highest: number; measured: number; maximum?: number | null; waiting: number }[];
  aiServers: { number: number; requestsAtOnce: number; maximum: number; learned: boolean }[];
  aiMaximum?: number | null;
  queueLength: number;
  intakeSlowdown: number;
  intakeStopped: boolean;
}

export interface ProductTelemetryStatus {
  mode: 'OFF' | 'BASIC' | 'ENHANCED' | string;
  enabled: boolean;
  installationIdConfigured: boolean;
  privacyContract: string;
  eventAllowList: string[];
  prohibitedData: string[];
}

export interface PlatformAdminOverview {
  generatedAt: string;
  providers: PlatformHealthItem[];
  watchedFolder: WatchedFolderAdminStatus;
  resilience: {
    maxAttempts: number;
    initialBackoffMs: number;
    packageProcessingMaxAttempts: number;
    aiSecondaryEnabled: boolean;
    aiSecondaryProvider: string;
    aiSecondaryModel: string;
  };
  integrations: {
    deliveryType: string;
    documentStoreConfigured: boolean;
    identityMode: string;
    productionIdentityConfigured: boolean;
  };
  technicalExceptions: TechnicalExceptionItem[];
}

export type ProcessingStorageProviderType = 'LOCAL_FILESYSTEM' | 'SHARED_FILESYSTEM';
export type ProcessingStorageProviderStatus = 'CANDIDATE' | 'AVAILABLE' | 'RETIRED' | string;

export interface ProcessingStorageProvider {
  id: string;
  name: string;
  providerType: ProcessingStorageProviderType | string;
  rootPath: string;
  status: ProcessingStorageProviderStatus;
  configurationVersion: number;
}

export interface ProcessingStorageAssignment {
  id: string;
  tenantId?: string | null;
  providerId: string;
  scope: 'DEPLOYMENT_DEFAULT' | 'TENANT_OVERRIDE' | string;
  activatedAt: string;
}

export interface ProcessingStorageNodeValidation {
  nodeId: string;
  processInstanceId: string;
  providerId: string;
  status: 'HEALTHY' | 'UNHEALTHY' | string;
  failureCode?: string | null;
  checkedAt: string;
}

export interface ProcessingStorageTenant {
  tenantId: string;
  name: string;
  slug: string;
  status: string;
}

export interface ProcessingStorageOverview {
  ready: boolean;
  deploymentDefault?: ProcessingStorageAssignment | null;
  tenantOverrides: ProcessingStorageAssignment[];
  providers: ProcessingStorageProvider[];
  nodeValidations: ProcessingStorageNodeValidation[];
  tenants: ProcessingStorageTenant[];
  activeNodeCount: number;
}

export interface CreateProcessingStorageProviderRequest {
  name: string;
  providerType: ProcessingStorageProviderType;
  rootPath: string;
}

/** Task 0057: Support, Status (GET /api/v1/support/status). */
export type { SupportStatusShown as SupportStatusView } from '../studio/support/statusRules';
/** Task 0057: the Licensing screen's read (GET /api/v1/licensing). */
export type { LicensingShown as LicensingView } from '../studio/admin/licensingRules';

/** An organization the signed-in person may choose, as listed by GET /api/v1/me/tenants. */
export interface TenantChoice {
  tenantId: string;
  name: string;
  slug: string;
  roles: string[];
  /** Task 0058: the entity this person worked in last. */
  lastUsed?: boolean;
}

/** The organization switch handed to the newest design: one implementation, owned by App. */
export interface TenantSwitchActions {
  /** Fresh list from the server, or null when the person has fewer than two organizations. */
  list: () => Promise<TenantChoice[] | null>;
  /** Enters the organization; false when the server refuses (nothing has changed). */
  enter: (tenantId: string) => Promise<boolean>;
}

export interface CurrentUser {
  username: string;
  /** Task 0058: the entity's time zone; null or absent when none is set. */
  timeZone?: string | null;
  roles: string[];
  tenantName: string;
  tenantSlug: string;
  authenticationMode: 'LOCAL' | 'OIDC' | string;
  capabilities: string[];
}

export interface TechnicalIncidentEvent {
  eventType: string;
  attemptNumber?: number | null;
  scope?: string | null;
  reasonCode?: string | null;
  retryable?: boolean | null;
  correlationId: string;
  occurredAt: string;
  safeMetadata: Record<string, string>;
}


export interface SupportIncidentSummary {
  incidentId: string;
  tenantId: string;
  packageId: string;
  documentId?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
  stage: string;
  component: string;
  reasonCode: string;
  /** Task 0025: the LV code for reasonCode, when the server sends it. */
  lvCode?: string | null;
  status: string;
  businessOutcome?: string | null;
  businessContinuityUsed: boolean;
  retryable: boolean;
  attemptCount: number;
  correlationId: string;
  businessImpact: 'BLOCKED' | 'CONTINUED' | 'COMPLETED' | string;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt?: string | null;
}

export interface SupportIncidentSearchPage {
  items: SupportIncidentSummary[];
  total: number;
  page: number;
  size: number;
}

export interface SupportIncidentDetail {
  incidentId: string;
  tenantId: string;
  packageId: string;
  documentId?: string | null;
  /** Task 0038: where the problem's document sits in its case ("document 2 of 3, page 14"). */
  documentPosition?: { position: number; count: number; page?: number | null } | null;
  packageStatus?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
  status: string;
  businessOutcome?: string | null;
  businessImpact: string;
  businessContinuityUsed: boolean;
  stage: string;
  component: string;
  failureCategory: string;
  reasonCode: string;
  /** Task 0025: the LV code for reasonCode, when the server sends it. */
  lvCode?: string | null;
  retryable: boolean;
  attemptCount: number;
  correlationId: string;
  safeSummary: string;
  artifactAvailability?: 'PURGED' | 'PENDING_PURGE' | 'RETAINED_UNTIL_POLICY_EXPIRY' | 'UNKNOWN' | string;
  technicalAttentionRetainedUntil?: string | null;
  artifactsPurgedAt?: string | null;
  processingFingerprint: Record<string, string>;
  safeMetadata: Record<string, string>;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt?: string | null;
  events: TechnicalIncidentEvent[];
}

export interface TechnicalExceptionDetail {
  packageId: string;
  documentId?: string | null;
  status: string;
  failureCategory: string;
  failedStage: string;
  retryable: boolean;
  failedAt: string;
  correlationId?: string | null;
  originalRetainedUntil?: string | null;
  explanation: string;
  /** Task 0025: the LV code of the failure, when the server sends it. */
  lvCode?: string | null;
  incidentId?: string | null;
  incidentStatus?: string | null;
  businessContinuityUsed?: boolean;
  businessOutcome?: string | null;
  processingFingerprint?: Record<string, string>;
  incidentEvents?: TechnicalIncidentEvent[];
  operatorDiagnostics?: {
    dependency: string;
    internalErrorCode: string;
    attempts: number;
    environment: string;
  } | null;
}

export interface EffectiveBranding {
  tenantId?: string | null;
  applicationName: string;
  shortName: string;
  organizationName?: string | null;
  pageTitle: string;
  secondaryColor: string;
  primaryLogoDataUrl?: string | null;
  compactLogoDataUrl?: string | null;
  faviconDataUrl?: string | null;
}

export interface BrandingConfiguration {
  id: string;
  tenantId?: string | null;
  applicationName?: string | null;
  shortName?: string | null;
  organizationName?: string | null;
  pageTitle?: string | null;
  secondaryColor?: string | null;
  primaryLogoDataUrl?: string | null;
  compactLogoDataUrl?: string | null;
  faviconDataUrl?: string | null;
  updatedBy: string;
  updatedAt: string;
}

export interface TenantBrandingSummary {
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  customBranding: boolean;
}

export interface TenantBrandingDetail {
  tenantId: string;
  customBranding: boolean;
  configuration?: BrandingConfiguration | null;
}

export interface BrandingOverview {
  deployment?: BrandingConfiguration | null;
  tenants: TenantBrandingSummary[];
}

export interface BrandingAssetUpdate {
  dataUrl?: string | null;
  clear?: boolean;
}

export interface BrandingUpdateRequest {
  applicationName?: string | null;
  shortName?: string | null;
  organizationName?: string | null;
  pageTitle?: string | null;
  secondaryColor?: string | null;
  primaryLogo?: BrandingAssetUpdate | null;
  compactLogo?: BrandingAssetUpdate | null;
  favicon?: BrandingAssetUpdate | null;
}

/** A sign-in connection as Admin › Sign-in shows it. It holds no secret. */
export interface EnterpriseIdentityConnection {
  connectionId: string;
  providerId: string;
  providerType: 'OIDC' | 'SAML' | 'LDAP_AD';
  displayName: string;
  issuerUri: string;
  browserClientId: string | null;
  apiAudience: string;
  status: 'DRAFT' | 'VALIDATED' | 'ACTIVE' | 'DISABLED';
  configurationVersion: number;
  /** Task 0058: whether this server signs people in with these values; only then can it be switched to. */
  matchesSettings?: boolean;
}

/** Task 0058: the connection in use, the draft of a change, what going back gives, and the newest changes. */
export interface EnterpriseIdentityOverview {
  active: EnterpriseIdentityConnection | null;
  draft?: EnterpriseIdentityConnection | null;
  previous?: EnterpriseIdentityConnection | null;
  history?: { issuerUri: string; version: number; status: string; source: string; changedBy: string; changedAt: string }[];
}

/** The answer to a step of a sign-in change. `reason` is the outbound rule's code when the test's call was refused. */
export interface SignInChangeAnswer { done: boolean; code: string; message: string; whatToDo: string | null; reason: string | null }

/** The answer of the read-only sign-in test (task 0054). `code` is null when it passed. */
export interface SignInTestAnswer {
  passed: boolean;
  code: string | null;
  message: string;
  reason: string | null;
  testedAt: string | null;
  /** Task 0057: what to do about a failed test. */
  whatToDo?: string | null;
}

/** One sign-in access change of the entity (platform-identity grant history, task 0041). */
export interface GrantHistoryRow {
  changedAt: string;
  change: 'ADDED' | 'REMOVED' | 'CHANGED';
  group: string;
  roleBefore: string | null;
  roleAfter: string | null;
  source: 'SETUP' | 'APP';
  changedBy: string;
  setupRunId: string | null;
  issuer: string;
}

/** Task 0058: the entity slots a Business Admin sees, and the answer to a change of one. */
export interface EntitySlot {
  slotId: string;
  name: string | null;
  status: string;
  current: boolean;
  timeZone: string | null;
  customerTimeZone: string | null;
  activatedAt: string | null;
}
export interface EntitySlotsOverview { entities: EntitySlot[] }
/** Task 0059: Admin › Notifications, and the answer to an approval link. */
export interface NotificationMail {
  enabled: boolean; mode: string; host: string | null; port: number | null; security: string | null; username: string | null;
  fromAddress: string | null; fromName: string | null; directoryId: string | null; clientId: string | null; credentialType: string | null;
  secretNamed: boolean;
}
export interface NotificationRoleCount { role: string; people: number }
export interface NotificationEvent { id: string; name: string; enabled: boolean; alwaysOn: boolean; frequency: string; frequencies: string[]; roles: string[]; dailyAt: string | null; alertAfterMinutes: number | null; repeatMinutes: number | null; alertAfterMax: number; thresholds: { name: string; label: string; value: number; min: number; max: number }[] }
export interface NotificationEventChange { enabled: boolean; frequency: string; dailyAt?: string; alertAfterMinutes?: number; repeatMinutes?: number; thresholds?: Record<string, number> }
export interface NotificationItOverview {
  mail: NotificationMail | null; appAddressSet: boolean; approvalLinksOn: boolean; secretNamePrefix: string; mailPorts: number[];
  people: NotificationRoleCount[]; events: NotificationEvent[];
  recent: { event: string; state: string; code: string | null; reason: string | null; at: string }[];
}
export interface NotificationBusinessOverview { mailOn: boolean; people: NotificationRoleCount[]; events: NotificationEvent[] }
export interface ApprovalAnswer { state: string; action: string | null; caseId: string | null; caseNumber: string | null; askedBy: string | null; expiresAt: string | null; code: string | null; message: string }

export interface EntityChangeAnswer { done: boolean; code: string; message: string; whatToDo: string | null }
